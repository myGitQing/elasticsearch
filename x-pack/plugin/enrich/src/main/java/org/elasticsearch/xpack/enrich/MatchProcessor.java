/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.enrich;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.routing.Preference;
import org.elasticsearch.index.query.ConstantScoreQueryBuilder;
import org.elasticsearch.index.query.TermQueryBuilder;
import org.elasticsearch.ingest.IngestDocument;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.xpack.core.enrich.EnrichPolicy;
import org.elasticsearch.xpack.enrich.action.EnrichCoordinatorProxyAction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

public final class MatchProcessor extends AbstractEnrichProcessor {

    private final BiConsumer<SearchRequest, BiConsumer<SearchResponse, Exception>> searchRunner;
    private final String field;
    private final String targetField;
    private final String matchField;
    private final boolean ignoreMissing;
    private final boolean overrideEnabled;
    private final int maxMatches;

    MatchProcessor(String tag,
                   Client client,
                   String policyName,
                   String field,
                   String targetField,
                   String matchField,
                   boolean ignoreMissing,
                   boolean overrideEnabled,
                   int maxMatches) {
        this(
            tag,
            createSearchRunner(client),
            policyName,
            field,
            targetField,
            matchField,
            ignoreMissing,
            overrideEnabled,
            maxMatches
        );
    }

    MatchProcessor(String tag,
                   BiConsumer<SearchRequest, BiConsumer<SearchResponse, Exception>> searchRunner,
                   String policyName,
                   String field,
                   String targetField,
                   String matchField,
                   boolean ignoreMissing,
                   boolean overrideEnabled,
                   int maxMatches) {
        super(tag, policyName);
        this.searchRunner = searchRunner;
        this.field = field;
        this.targetField = targetField;
        this.matchField = matchField;
        this.ignoreMissing = ignoreMissing;
        this.overrideEnabled = overrideEnabled;
        this.maxMatches = maxMatches;
    }

    @Override
    public void execute(IngestDocument ingestDocument, BiConsumer<IngestDocument, Exception> handler) {
        try {
            // If a document does not have the enrich key, return the unchanged document
            final String value = ingestDocument.getFieldValue(field, String.class, ignoreMissing);
            if (value == null) {
                handler.accept(ingestDocument, null);
                return;
            }

            TermQueryBuilder termQuery = new TermQueryBuilder(matchField, value);
            ConstantScoreQueryBuilder constantScore = new ConstantScoreQueryBuilder(termQuery);
            SearchSourceBuilder searchBuilder = new SearchSourceBuilder();
            searchBuilder.from(0);
            searchBuilder.size(maxMatches);
            searchBuilder.trackScores(false);
            searchBuilder.fetchSource(true);
            searchBuilder.query(constantScore);

            SearchRequest req = new SearchRequest();
            req.indices(EnrichPolicy.getBaseName(getPolicyName()));
            req.preference(Preference.LOCAL.type());
            req.source(searchBuilder);

            searchRunner.accept(req, (searchResponse, e) -> {
                if (e != null) {
                    handler.accept(null, e);
                    return;
                }

                // If the index is empty, return the unchanged document
                // If the enrich key does not exist in the index, throw an error
                // If no documents match the key, return the unchanged document
                SearchHit[] searchHits = searchResponse.getHits().getHits();
                if (searchHits.length < 1) {
                    handler.accept(ingestDocument, null);
                    return;
                }

                if (overrideEnabled || ingestDocument.hasField(targetField) == false) {
                    List<Map<String, Object>> enrichDocuments = new ArrayList<>(searchHits.length);
                    for (SearchHit searchHit : searchHits) {
                        Map<String, Object> enrichDocument = searchHit.getSourceAsMap();
                        enrichDocuments.add(enrichDocument);
                    }
                    ingestDocument.setFieldValue(targetField, enrichDocuments);
                }
                handler.accept(ingestDocument, null);
            });
        } catch (Exception e) {
            handler.accept(null, e);
        }
    }

    @Override
    public IngestDocument execute(IngestDocument ingestDocument) throws Exception {
        throw new UnsupportedOperationException("this method should not get executed");
    }

    @Override
    public String getType() {
        return EnrichProcessorFactory.TYPE;
    }

    String getField() {
        return field;
    }

    public String getTargetField() {
        return targetField;
    }

    public String getMatchField() {
        return matchField;
    }

    boolean isIgnoreMissing() {
        return ignoreMissing;
    }

    boolean isOverrideEnabled() {
        return overrideEnabled;
    }

    int getMaxMatches() {
        return maxMatches;
    }

    private static BiConsumer<SearchRequest, BiConsumer<SearchResponse, Exception>> createSearchRunner(Client client) {
        return (req, handler) -> {
            client.execute(EnrichCoordinatorProxyAction.INSTANCE, req, ActionListener.wrap(
                resp -> {
                    handler.accept(resp, null);
                },
                e -> {
                    handler.accept(null, e);
                }));
        };
    }
}