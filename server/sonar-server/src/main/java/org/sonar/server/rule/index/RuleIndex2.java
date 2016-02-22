/*
 * SonarQube
 * Copyright (C) 2009-2016 SonarSource SA
 * mailto:contact AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.rule.index;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.query.BoolFilterBuilder;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.FilterBuilder;
import org.elasticsearch.index.query.FilterBuilders;
import org.elasticsearch.index.query.HasParentFilterBuilder;
import org.elasticsearch.index.query.MatchQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.SimpleQueryStringBuilder;
import org.elasticsearch.search.aggregations.AggregationBuilder;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.aggregations.bucket.terms.TermsBuilder;
import org.elasticsearch.search.sort.FieldSortBuilder;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;
import org.sonar.api.rule.RuleKey;
import org.sonar.api.rule.RuleStatus;
import org.sonar.api.rule.Severity;
import org.sonar.server.es.BaseIndex;
import org.sonar.server.es.EsClient;
import org.sonar.server.es.SearchIdResult;
import org.sonar.server.es.SearchOptions;
import org.sonar.server.qualityprofile.index.ActiveRuleNormalizer;
import org.sonar.server.search.IndexDefinition;
import org.sonar.server.search.IndexField;
import org.sonar.server.search.StickyFacetBuilder;

import static org.sonar.server.es.EsUtils.SCROLL_TIME_IN_MINUTES;
import static org.sonar.server.es.EsUtils.scrollIds;

/**
 * The unique entry-point to interact with Elasticsearch index "rules".
 * All the requests are listed here.
 */
public class RuleIndex2 extends BaseIndex {

  public static final String FACET_LANGUAGES = "languages";
  public static final String FACET_TAGS = "tags";
  public static final String FACET_REPOSITORIES = "repositories";
  public static final String FACET_SEVERITIES = "severities";
  public static final String FACET_ACTIVE_SEVERITIES = "active_severities";
  public static final String FACET_STATUSES = "statuses";
  public static final String FACET_OLD_DEFAULT = "true";

  public static final List<String> ALL_STATUSES_EXCEPT_REMOVED = ImmutableList.copyOf(
    Collections2.filter(
      Collections2.transform(
        Arrays.asList(RuleStatus.values()),
        new Function<RuleStatus, String>() {
          @Override
          public String apply(@Nonnull RuleStatus input) {
            return input.toString();
          }
        }),
      new Predicate<String>() {
        @Override
        public boolean apply(@Nonnull String input) {
          return !RuleStatus.REMOVED.toString().equals(input);
        }
      }));

  public RuleIndex2(EsClient client) {
    super(client);
  }

  public SearchIdResult<RuleKey> search(RuleQuery query, SearchOptions options) {
    SearchRequestBuilder esSearch = getClient()
      .prepareSearch(RuleIndexDefinition.INDEX)
      .setTypes(RuleIndexDefinition.TYPE_RULE);

    QueryBuilder qb = buildQuery(query);
    Map<String, FilterBuilder> filters = buildFilters(query);

    if (!options.getFacets().isEmpty()) {
      for (AggregationBuilder aggregation : getFacets(query, options, qb, filters).values()) {
        esSearch.addAggregation(aggregation);
      }
    }

    setSorting(query, esSearch);
    setPagination(options, esSearch);

    BoolFilterBuilder fb = FilterBuilders.boolFilter();
    for (FilterBuilder filterBuilder : filters.values()) {
      fb.must(filterBuilder);
    }

    esSearch.setQuery(QueryBuilders.filteredQuery(qb, fb));
    return new SearchIdResult<>(esSearch.get(), ToRuleKey.INSTANCE);
  }

  /**
   * Return all keys matching the search query, without pagination nor facets
   */
  public Iterator<RuleKey> searchAll(RuleQuery query) {
    SearchRequestBuilder esSearch = getClient()
      .prepareSearch(RuleIndexDefinition.INDEX)
      .setTypes(RuleIndexDefinition.TYPE_RULE)
      .setSearchType(SearchType.SCAN)
      .setScroll(TimeValue.timeValueMinutes(SCROLL_TIME_IN_MINUTES));

    QueryBuilder qb = buildQuery(query);
    Map<String, FilterBuilder> filters = buildFilters(query);
    setSorting(query, esSearch);

    BoolFilterBuilder fb = FilterBuilders.boolFilter();
    for (FilterBuilder filterBuilder : filters.values()) {
      fb.must(filterBuilder);
    }

    esSearch.setQuery(QueryBuilders.filteredQuery(qb, fb));
    SearchResponse response = esSearch.get();
    return scrollIds(getClient(), response.getScrollId(), ToRuleKey.INSTANCE);
  }

  /* Build main query (search based) */
  private QueryBuilder buildQuery(RuleQuery query) {

    // No contextual query case
    String queryText = query.getQueryText();
    if (queryText == null || queryText.isEmpty()) {
      return QueryBuilders.matchAllQuery();
    }

    // Build RuleBased contextual query
    BoolQueryBuilder qb = QueryBuilders.boolQuery();
    String queryString = query.getQueryText();

    // Human readable type of querying
    qb.should(QueryBuilders.simpleQueryStringQuery(query.getQueryText())
      .field(RuleIndexDefinition.FIELD_RULE_NAME + "." + BaseIndex.SEARCH_WORDS_SUFFIX, 20f)
      .field(RuleIndexDefinition.FIELD_RULE_HTML_DESCRIPTION + "." + BaseIndex.SEARCH_WORDS_SUFFIX, 3f)
      .defaultOperator(SimpleQueryStringBuilder.Operator.AND)
      ).boost(20f);

    // Match and partial Match queries
    qb.should(this.termQuery(RuleIndexDefinition.FIELD_RULE_KEY, queryString, 15f));
    qb.should(this.termQuery(RuleIndexDefinition.FIELD_RULE_KEY_AS_LIST, queryString, 35f));
    qb.should(this.termQuery(RuleIndexDefinition.FIELD_RULE_LANGUAGE, queryString, 3f));
    qb.should(this.termQuery(RuleIndexDefinition.FIELD_RULE_ALL_TAGS, queryString, 10f));
    qb.should(this.termAnyQuery(RuleIndexDefinition.FIELD_RULE_ALL_TAGS, queryString, 1f));

    return qb;
  }

  private QueryBuilder termQuery(String field, String query, float boost) {
    return QueryBuilders.multiMatchQuery(query,
      field, field + "." + IndexField.SEARCH_PARTIAL_SUFFIX)
      .operator(MatchQueryBuilder.Operator.AND)
      .boost(boost);
  }

  private QueryBuilder termAnyQuery(String field, String query, float boost) {
    return QueryBuilders.multiMatchQuery(query,
      field, field + "." + IndexField.SEARCH_PARTIAL_SUFFIX)
      .operator(MatchQueryBuilder.Operator.OR)
      .boost(boost);
  }

  /* Build main filter (match based) */
  private Map<String, FilterBuilder> buildFilters(RuleQuery query) {

    Map<String, FilterBuilder> filters = new HashMap<>();

    /* Add enforced filter on rules that are REMOVED */
    filters.put(RuleIndexDefinition.FIELD_RULE_STATUS,
      FilterBuilders.boolFilter().mustNot(
        FilterBuilders.termFilter(RuleIndexDefinition.FIELD_RULE_STATUS,
          RuleStatus.REMOVED.toString())));

    if (!StringUtils.isEmpty(query.getInternalKey())) {
      filters.put(RuleIndexDefinition.FIELD_RULE_INTERNAL_KEY,
        FilterBuilders.termFilter(RuleIndexDefinition.FIELD_RULE_INTERNAL_KEY, query.getInternalKey()));
    }

    if (!StringUtils.isEmpty(query.getRuleKey())) {
      filters.put(RuleIndexDefinition.FIELD_RULE_RULE_KEY,
        FilterBuilders.termFilter(RuleIndexDefinition.FIELD_RULE_RULE_KEY, query.getRuleKey()));
    }

    if (!CollectionUtils.isEmpty(query.getLanguages())) {
      filters.put(RuleIndexDefinition.FIELD_RULE_LANGUAGE,
        FilterBuilders.termsFilter(RuleIndexDefinition.FIELD_RULE_LANGUAGE, query.getLanguages()));
    }

    if (!CollectionUtils.isEmpty(query.getRepositories())) {
      filters.put(RuleIndexDefinition.FIELD_RULE_REPOSITORY,
        FilterBuilders.termsFilter(RuleIndexDefinition.FIELD_RULE_REPOSITORY, query.getRepositories()));
    }

    if (!CollectionUtils.isEmpty(query.getSeverities())) {
      filters.put(RuleIndexDefinition.FIELD_RULE_SEVERITY,
        FilterBuilders.termsFilter(RuleIndexDefinition.FIELD_RULE_SEVERITY, query.getSeverities()));
    }

    if (!StringUtils.isEmpty(query.getKey())) {
      filters.put(RuleIndexDefinition.FIELD_RULE_KEY,
        FilterBuilders.termFilter(RuleIndexDefinition.FIELD_RULE_KEY, query.getKey()));
    }

    if (!CollectionUtils.isEmpty(query.getTags())) {
      filters.put(RuleIndexDefinition.FIELD_RULE_ALL_TAGS,
        FilterBuilders.termsFilter(RuleIndexDefinition.FIELD_RULE_ALL_TAGS, query.getTags()));
    }

    if (query.getAvailableSinceLong() != null) {
      filters.put("availableSince", FilterBuilders.rangeFilter(RuleIndexDefinition.FIELD_RULE_CREATED_AT)
        .gte(query.getAvailableSinceLong()));
    }

    Collection<RuleStatus> statusValues = query.getStatuses();
    if (statusValues != null && !statusValues.isEmpty()) {
      Collection<String> stringStatus = new ArrayList<>();
      for (RuleStatus status : statusValues) {
        stringStatus.add(status.name());
      }
      filters.put(RuleIndexDefinition.FIELD_RULE_STATUS,
        FilterBuilders.termsFilter(RuleIndexDefinition.FIELD_RULE_STATUS, stringStatus));
    }

    Boolean isTemplate = query.isTemplate();
    if (isTemplate != null) {
      filters.put(RuleIndexDefinition.FIELD_RULE_IS_TEMPLATE,
        FilterBuilders.termFilter(RuleIndexDefinition.FIELD_RULE_IS_TEMPLATE, Boolean.toString(isTemplate)));
    }

    String template = query.templateKey();
    if (template != null) {
      filters.put(RuleIndexDefinition.FIELD_RULE_TEMPLATE_KEY,
        FilterBuilders.termFilter(RuleIndexDefinition.FIELD_RULE_TEMPLATE_KEY, template));
    }

    // ActiveRule Filter (profile and inheritance)
    BoolFilterBuilder childrenFilter = FilterBuilders.boolFilter();
    this.addTermFilter(childrenFilter, ActiveRuleNormalizer.ActiveRuleField.PROFILE_KEY.field(), query.getQProfileKey());
    this.addTermFilter(childrenFilter, ActiveRuleNormalizer.ActiveRuleField.INHERITANCE.field(), query.getInheritance());
    this.addTermFilter(childrenFilter, ActiveRuleNormalizer.ActiveRuleField.SEVERITY.field(), query.getActiveSeverities());

    // ChildQuery
    FilterBuilder childQuery;
    if (childrenFilter.hasClauses()) {
      childQuery = childrenFilter;
    } else {
      childQuery = FilterBuilders.matchAllFilter();
    }

    /** Implementation of activation query */
    if (Boolean.TRUE.equals(query.getActivation())) {
      filters.put("activation",
        FilterBuilders.hasChildFilter(IndexDefinition.ACTIVE_RULE.getIndexType(),
          childQuery));
    } else if (Boolean.FALSE.equals(query.getActivation())) {
      filters.put("activation",
        FilterBuilders.boolFilter().mustNot(
          FilterBuilders.hasChildFilter(IndexDefinition.ACTIVE_RULE.getIndexType(),
            childQuery)));
    }

    return filters;
  }

  private BoolFilterBuilder addTermFilter(BoolFilterBuilder filter, String field, @Nullable Collection<String> values) {
    if (values != null && !values.isEmpty()) {
      BoolFilterBuilder valuesFilter = FilterBuilders.boolFilter();
      for (String value : values) {
        FilterBuilder valueFilter = FilterBuilders.termFilter(field, value);
        valuesFilter.should(valueFilter);
      }
      filter.must(valuesFilter);
    }
    return filter;
  }

  private BoolFilterBuilder addTermFilter(BoolFilterBuilder filter, String field, @Nullable String value) {
    if (value != null && !value.isEmpty()) {
      filter.must(FilterBuilders.termFilter(field, value));
    }
    return filter;
  }

  private Map<String, AggregationBuilder> getFacets(RuleQuery query, SearchOptions options, QueryBuilder queryBuilder, Map<String, FilterBuilder> filters) {
    Map<String, AggregationBuilder> aggregations = new HashMap<>();
    StickyFacetBuilder stickyFacetBuilder = stickyFacetBuilder(queryBuilder, filters);

    addDefaultFacets(query, options, aggregations, stickyFacetBuilder);

    addStatusFacetIfNeeded(options, aggregations, stickyFacetBuilder);

    if (options.getFacets().contains(FACET_SEVERITIES)) {
      aggregations.put(FACET_SEVERITIES,
        stickyFacetBuilder.buildStickyFacet(RuleIndexDefinition.FIELD_RULE_SEVERITY, FACET_SEVERITIES, Severity.ALL.toArray()));
    }

    addActiveSeverityFacetIfNeeded(query, options, aggregations, stickyFacetBuilder);
    return aggregations;
  }

  private void addDefaultFacets(RuleQuery query, SearchOptions options, Map<String, AggregationBuilder> aggregations, StickyFacetBuilder stickyFacetBuilder) {
    if (options.getFacets().contains(FACET_LANGUAGES) || options.getFacets().contains(FACET_OLD_DEFAULT)) {
      Collection<String> languages = query.getLanguages();
      aggregations.put(FACET_LANGUAGES,
        stickyFacetBuilder.buildStickyFacet(RuleIndexDefinition.FIELD_RULE_LANGUAGE, FACET_LANGUAGES,
          languages == null ? new String[0] : languages.toArray()));
    }
    if (options.getFacets().contains(FACET_TAGS) || options.getFacets().contains(FACET_OLD_DEFAULT)) {
      Collection<String> tags = query.getTags();
      aggregations.put(FACET_TAGS,
        stickyFacetBuilder.buildStickyFacet(RuleIndexDefinition.FIELD_RULE_ALL_TAGS, FACET_TAGS,
          tags == null ? new String[0] : tags.toArray()));
    }
    if (options.getFacets().contains("repositories") || options.getFacets().contains(FACET_OLD_DEFAULT)) {
      Collection<String> repositories = query.getRepositories();
      aggregations.put(FACET_REPOSITORIES,
        stickyFacetBuilder.buildStickyFacet(RuleIndexDefinition.FIELD_RULE_REPOSITORY, FACET_REPOSITORIES,
          repositories == null ? new String[0] : repositories.toArray()));
    }
  }

  private void addStatusFacetIfNeeded(SearchOptions options, Map<String, AggregationBuilder> aggregations, StickyFacetBuilder stickyFacetBuilder) {
    if (options.getFacets().contains(FACET_STATUSES)) {
      BoolFilterBuilder facetFilter = stickyFacetBuilder.getStickyFacetFilter(RuleIndexDefinition.FIELD_RULE_STATUS);
      AggregationBuilder statuses = AggregationBuilders.filter(FACET_STATUSES + "_filter")
        .filter(facetFilter)
        .subAggregation(
          AggregationBuilders
            .terms(FACET_STATUSES)
            .field(RuleIndexDefinition.FIELD_RULE_STATUS)
            .include(Joiner.on('|').join(ALL_STATUSES_EXCEPT_REMOVED))
            .exclude(RuleStatus.REMOVED.toString())
            .size(ALL_STATUSES_EXCEPT_REMOVED.size()));

      aggregations.put(FACET_STATUSES, AggregationBuilders.global(FACET_STATUSES).subAggregation(statuses));
    }
  }

  private void addActiveSeverityFacetIfNeeded(RuleQuery query, SearchOptions options, Map<String, AggregationBuilder> aggregations, StickyFacetBuilder stickyFacetBuilder) {
    if (options.getFacets().contains(FACET_ACTIVE_SEVERITIES)) {
      // We are building a children aggregation on active rules
      // so the rule filter has to be used as parent filter for active rules
      // from which we remove filters that concern active rules ("activation")
      HasParentFilterBuilder ruleFilter = FilterBuilders.hasParentFilter(
        IndexDefinition.RULE.getIndexType(),
        stickyFacetBuilder.getStickyFacetFilter("activation"));

      // Rebuilding the active rule filter without severities
      BoolFilterBuilder childrenFilter = FilterBuilders.boolFilter();
      this.addTermFilter(childrenFilter, ActiveRuleNormalizer.ActiveRuleField.PROFILE_KEY.field(), query.getQProfileKey());
      this.addTermFilter(childrenFilter, ActiveRuleNormalizer.ActiveRuleField.INHERITANCE.field(), query.getInheritance());
      FilterBuilder activeRuleFilter;
      if (childrenFilter.hasClauses()) {
        activeRuleFilter = childrenFilter.must(ruleFilter);
      } else {
        activeRuleFilter = ruleFilter;
      }

      AggregationBuilder activeSeverities = AggregationBuilders.children(FACET_ACTIVE_SEVERITIES + "_children")
        .childType(IndexDefinition.ACTIVE_RULE.getIndexType())
        .subAggregation(AggregationBuilders.filter(FACET_ACTIVE_SEVERITIES + "_filter")
          .filter(activeRuleFilter)
          .subAggregation(
            AggregationBuilders
              .terms(FACET_ACTIVE_SEVERITIES)
              .field(ActiveRuleNormalizer.ActiveRuleField.SEVERITY.field())
              .include(Joiner.on('|').join(Severity.ALL))
              .size(Severity.ALL.size())));

      aggregations.put(FACET_ACTIVE_SEVERITIES, AggregationBuilders.global(FACET_ACTIVE_SEVERITIES).subAggregation(activeSeverities));
    }
  }

  private StickyFacetBuilder stickyFacetBuilder(QueryBuilder query, Map<String, FilterBuilder> filters) {
    return new StickyFacetBuilder(query, filters);
  }

  private void setSorting(RuleQuery query, SearchRequestBuilder esSearch) {
    /* integrate Query Sort */
    String queryText = query.getQueryText();
    if (query.getSortField() != null) {
      FieldSortBuilder sort = SortBuilders.fieldSort(appendSortSuffixIfNeeded(query.getSortField()));
      if (query.isAscendingSort()) {
        sort.order(SortOrder.ASC);
      } else {
        sort.order(SortOrder.DESC);
      }
      esSearch.addSort(sort);
    } else if (queryText != null && !queryText.isEmpty()) {
      esSearch.addSort(SortBuilders.scoreSort());
    } else {
      esSearch.addSort(appendSortSuffixIfNeeded(RuleIndexDefinition.FIELD_RULE_UPDATED_AT), SortOrder.DESC);
      // deterministic sort when exactly the same updated_at (same millisecond)
      esSearch.addSort(appendSortSuffixIfNeeded(RuleIndexDefinition.FIELD_RULE_KEY), SortOrder.ASC);
    }
  }

  public static String appendSortSuffixIfNeeded(String field) {
      return field +
        ((field.equals(RuleIndexDefinition.FIELD_RULE_NAME)
        || field.equals(RuleIndexDefinition.FIELD_RULE_KEY))
          ? "." + BaseIndex.SORT_SUFFIX
          : "");
  }

  private void setPagination(SearchOptions options, SearchRequestBuilder esSearch) {
    esSearch.setFrom(options.getOffset());
    esSearch.setSize(options.getLimit());
  }

  public Set<String> terms(String fields) {
    return terms(fields, null, Integer.MAX_VALUE);
  }

  public Set<String> terms(String fields, @Nullable String query, int size) {
    Set<String> tags = new HashSet<>();
    String key = "_ref";

    TermsBuilder terms = AggregationBuilders.terms(key)
      .field(fields)
      .size(size)
      .minDocCount(1);
    if (query != null) {
      terms.include(".*" + query + ".*");
    }
    SearchRequestBuilder request = this.getClient()
      .prepareSearch(RuleIndexDefinition.INDEX)
      .setQuery(QueryBuilders.matchAllQuery())
      .addAggregation(terms);

    SearchResponse esResponse = request.get();

    Terms aggregation = esResponse.getAggregations().get(key);

    if (aggregation != null) {
      for (Terms.Bucket value : aggregation.getBuckets()) {
        tags.add(value.getKey());
      }
    }
    return tags;
  }

  private enum ToRuleKey implements Function<String, RuleKey> {
    INSTANCE;

    @Override
    public RuleKey apply(@Nonnull String input) {
      return RuleKey.parse(input);
    }
  }

}