/* Generated By:JJTree: Do not edit this line. OMatchStatement.java Version 4.3 */
/* JavaCCOptions:MULTI=true,NODE_USES_PARSER=false,VISITOR=true,TRACK_TOKENS=true,NODE_PREFIX=O,NODE_EXTENDS=,NODE_FACTORY=,SUPPORT_CLASS_VISIBILITY_PUBLIC=true */
package com.orientechnologies.orient.core.sql.parser;

import com.orientechnologies.common.listener.OProgressListener;
import com.orientechnologies.orient.core.command.OCommandContext;
import com.orientechnologies.orient.core.command.OCommandExecutor;
import com.orientechnologies.orient.core.command.OCommandRequest;
import com.orientechnologies.orient.core.command.OCommandRequestText;
import com.orientechnologies.orient.core.db.ODatabaseDocumentInternal;
import com.orientechnologies.orient.core.db.document.ODatabaseDocument;
import com.orientechnologies.orient.core.db.record.OIdentifiable;
import com.orientechnologies.orient.core.exception.OCommandExecutionException;
import com.orientechnologies.orient.core.iterator.ORecordIteratorClass;
import com.orientechnologies.orient.core.metadata.schema.OClass;
import com.orientechnologies.orient.core.metadata.schema.OSchema;
import com.orientechnologies.orient.core.metadata.security.ORole;
import com.orientechnologies.orient.core.metadata.security.ORule;
import com.orientechnologies.orient.core.record.ORecord;
import com.orientechnologies.orient.core.sql.OCommandSQLParsingException;
import com.orientechnologies.orient.core.sql.query.OResultSet;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.*;

public class OMatchStatement extends OStatement implements OCommandExecutor {

  class MatchContext {
    String                root;
    Map<String, Iterable> candidates = new HashMap<String, Iterable>();
    Map<String, ORid>     matched    = new HashMap<String, ORid>();
  }

  class Pattern {
    Map<String, PatternNode> aliasToNode = new HashMap<String, PatternNode>();

    void addExpression(OMatchExpression expression) {
      PatternNode originNode = getOrCreateNode(expression.origin);

      for (OMatchPathItem item : expression.items) {
        String nextAlias = item.filter.getAlias();
        PatternNode nextNode = getOrCreateNode(item.filter);
        originNode.addEdge(item, nextNode);
        originNode = nextNode;
      }
    }

    private PatternNode getOrCreateNode(OMatchFilter origin) {
      PatternNode originNode = get(origin.getAlias());
      if (originNode == null) {
        originNode = new PatternNode();
        originNode.alias = origin.getAlias();
        aliasToNode.put(originNode.alias, originNode);
      }
      return originNode;
    }

    PatternNode get(String alias) {
      return aliasToNode.get(alias);
    }
  }

  class PatternNode {
    String           alias;
    Set<PatternEdge> out = new HashSet<PatternEdge>();
    Set<PatternEdge> in  = new HashSet<PatternEdge>();

    void addEdge(OMatchPathItem item, PatternNode to) {
      PatternEdge edge = new PatternEdge();
      edge.item = item;
      edge.out = this;
      edge.in = to;
      this.out.add(edge);
      to.in.add(edge);
    }
  }

  class PatternEdge {
    PatternNode    in;
    PatternNode    out;
    OMatchPathItem item;
  }

  public static final String       KEYWORD_MATCH    = "MATCH";
  // parsed data
  protected List<OMatchExpression> matchExpressions = new ArrayList<OMatchExpression>();
  protected List<OIdentifier>      returnItems      = new ArrayList<OIdentifier>();

  protected Pattern                pattern;

  // execution data
  private OCommandContext          context;
  private OProgressListener        progressListener;

  public OMatchStatement() {
    super(-1);
  }

  public OMatchStatement(int id) {
    super(id);
  }

  public OMatchStatement(OrientSql p, int id) {
    super(p, id);
  }

  /**
   * Accept the visitor. *
   */
  public Object jjtAccept(OrientSqlVisitor visitor, Object data) {
    return visitor.visit(this, data);
  }

  @Override
  public <RET extends OCommandExecutor> RET parse(OCommandRequest iRequest) {
    final OCommandRequestText textRequest = (OCommandRequestText) iRequest;
    String queryText = textRequest.getText();

    // please, do not look at this... refactor this ASAP with new executor structure
    final InputStream is = new ByteArrayInputStream(queryText.getBytes());
    final OrientSql osql = new OrientSql(is);
    try {
      OMatchStatement result = (OMatchStatement) osql.parse();
      this.matchExpressions = result.matchExpressions;
      this.returnItems = result.returnItems;
    } catch (ParseException e) {
      throw new OCommandSQLParsingException(e.getMessage(), e);
    }

    assignDefaultAliases(this.matchExpressions);
    pattern = new Pattern();
    for (OMatchExpression expr : this.matchExpressions) {
      pattern.addExpression(expr);
    }

    return (RET) this;
  }

  private void assignDefaultAliases(List<OMatchExpression> matchExpressions) {
    String defaultAliasPrefix = "$ORIENT_DEFAULT_ALIAS_";
    int counter = 0;
    for (OMatchExpression expression : matchExpressions) {
      if (expression.origin.getAlias() == null) {
        expression.origin.setAlias(defaultAliasPrefix + (counter++));
      }

      for (OMatchPathItem item : expression.items) {
        if (item.filter == null) {
          item.filter = new OMatchFilter(-1);
        }
        if (item.filter.getAlias() == null) {
          item.filter.setAlias(defaultAliasPrefix + (counter++));
        }
      }
    }
  }

  @Override
  public Object execute(Map<Object, Object> iArgs) {

    Map<String, OWhereClause> aliasFilters = new HashMap<String, OWhereClause>();
    Map<String, String> aliasClasses = new HashMap<String, String>();
    for (OMatchExpression expr : this.matchExpressions) {
      addAliases(expr, aliasFilters, aliasClasses);
    }

    Map<String, Long> estimatedRootEntries = estimateRootEntries(aliasClasses, aliasFilters);
    if (estimatedRootEntries.values().contains(0l)) {
      return new OResultSet();// some aliases do not match on any classes
    }

    return calculateMatch(estimatedRootEntries, new MatchContext(), aliasClasses, aliasFilters);
  }

  private OResultSet calculateMatch(Map<String, Long> estimatedRootEntries, MatchContext matchContext,
      Map<String, String> aliasClasses, Map<String, OWhereClause> aliasFilters) {
    OResultSet result = new OResultSet();
    calculateMatch(estimatedRootEntries, matchContext, aliasClasses, aliasFilters, result);
    return result;
  }

  private void calculateMatch(Map<String, Long> estimatedRootEntries, MatchContext matchContext, Map<String, String> aliasClasses,
      Map<String, OWhereClause> aliasFilters, OResultSet result) {
    String nextAlias = getNextAlias(estimatedRootEntries, matchContext);
    if (nextAlias == null) {
      return;
    }
    // Iterable aliasMatches = reach()
    // aliasMatches = query(aliasClasses.get(nextAlias), aliasFilters.get(nextAlias));
    // TODO start from here!

  }

  private Iterable<OIdentifiable> query(String className, OWhereClause oWhereClause) {
    final ODatabaseDocument database = getDatabase();
    OClass schemaClass = database.getMetadata().getSchema().getClass(className);
    database.checkSecurity(ORule.ResourceGeneric.CLASS, ORole.PERMISSION_READ, schemaClass.getName().toLowerCase());

    Iterable<ORecord> baseIterable = fetchFromIndex(schemaClass, oWhereClause);
    if (baseIterable == null) {
      baseIterable = new ORecordIteratorClass<ORecord>((ODatabaseDocumentInternal) database, (ODatabaseDocumentInternal) database,
          className, true, true);
    }
    Iterable<OIdentifiable> result = new FilteredIterator(baseIterable, oWhereClause);
    return result;
  }

  private Iterable<ORecord> fetchFromIndex(OClass schemaClass, OWhereClause oWhereClause) {
    return null;// TODO
  }

  private String getNextAlias(Map<String, Long> estimatedRootEntries, MatchContext matchContext) {
    Map.Entry<String, Long> lowerValue = null;
    for (Map.Entry<String, Long> entry : estimatedRootEntries.entrySet()) {
      if (matchContext.matched.containsKey(entry.getKey())) {
        continue;
      }
      if (lowerValue == null) {
        lowerValue = entry;
      } else if (lowerValue.getValue() > entry.getValue()) {
        lowerValue = entry;
      }
    }
    return lowerValue.getKey();
  }

  private Map<String, Long> estimateRootEntries(Map<String, String> aliasClasses, Map<String, OWhereClause> aliasFilters) {
    Set<String> allAliases = new HashSet<String>();
    allAliases.addAll(aliasClasses.keySet());
    allAliases.addAll(aliasFilters.keySet());

    OSchema schema = getDatabase().getMetadata().getSchema();

    Map<String, Long> result = new HashMap<String, Long>();
    for (String alias : allAliases) {
      String className = aliasClasses.get(alias);
      if (className == null) {
        continue;
      }

      if (!schema.existsClass(className)) {
        throw new OCommandExecutionException("class not defined: " + className);
      }
      OClass oClass = schema.getClass(alias);
      long upperBound;
      OWhereClause filter = aliasFilters.get(alias);
      if (filter != null) {
        upperBound = filter.estimate(oClass);
      } else {
        upperBound = oClass.count();
      }
      result.put(alias, upperBound);
    }
    return result;
  }

  private void addAliases(OMatchExpression expr, Map<String, OWhereClause> aliasFilters, Map<String, String> aliasClasses) {
    addAliases(expr.origin, aliasFilters, aliasClasses);
    for (OMatchPathItem item : expr.items) {
      if (item.filter != null) {
        addAliases(item.filter, aliasFilters, aliasClasses);
      }
    }
  }

  private void addAliases(OMatchFilter matchFilter, Map<String, OWhereClause> aliasFilters, Map<String, String> aliasClasses) {
    String alias = matchFilter.getAlias();
    OWhereClause filter = matchFilter.getFilter();
    if (alias != null) {
      if (filter != null && filter.baseExpression != null) {
        OWhereClause previousFilter = aliasFilters.get(alias);
        if (previousFilter == null) {
          previousFilter = new OWhereClause(-1);
          previousFilter.baseExpression = new OAndBlock(-1);
          aliasFilters.put(alias, previousFilter);
        }
        OAndBlock filterBlock = (OAndBlock) previousFilter.baseExpression;
        if (filter != null && filter.baseExpression != null) {
          filterBlock.subBlocks.add(filter.baseExpression);
        }
      }

      String clazz = matchFilter.getClassName();
      if (clazz != null) {
        String previousClass = aliasClasses.get(alias);
        if (previousClass == null) {
          aliasClasses.put(alias, clazz);
        } else {
          String lower = getLowerSubclass(clazz, previousClass);
          if (lower == null) {
            throw new OCommandExecutionException("classes defined for alias " + alias + " (" + clazz + ", " + previousClass
                + ") are not in the same hierarchy");
          }
          aliasClasses.put(alias, lower);
        }
      }
    }
  }

  private String getLowerSubclass(String className1, String className2) {
    OSchema schema = getDatabase().getMetadata().getSchema();
    OClass class1 = schema.getClass(className1);
    OClass class2 = schema.getClass(className2);
    if (class1.isSubClassOf(class2)) {
      return class1.getName();
    }
    if (class2.isSubClassOf(class1)) {
      return class2.getName();
    }
    return null;
  }

  @Override
  public <RET extends OCommandExecutor> RET setProgressListener(OProgressListener progressListener) {

    this.progressListener = progressListener;
    return (RET) this;
  }

  @Override
  public <RET extends OCommandExecutor> RET setLimit(int iLimit) {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getFetchPlan() {
    return null;
  }

  @Override
  public Map<Object, Object> getParameters() {
    return null;
  }

  @Override
  public OCommandContext getContext() {
    return context;
  }

  @Override
  public void setContext(OCommandContext context) {
    this.context = context;
  }

  @Override
  public boolean isIdempotent() {
    return true;
  }

  @Override
  public Set<String> getInvolvedClusters() {
    return Collections.EMPTY_SET;
  }

  @Override
  public int getSecurityOperationType() {
    return ORole.PERMISSION_READ;
  }

  @Override
  public boolean involveSchema() {
    return false;
  }

  @Override
  public long getTimeout() {
    return -1;
  }

  @Override
  public String getSyntax() {
    return "MATCH <match-statement> [, <match-statement] RETURN <alias>[, <alias>]";
  }
}
/* JavaCC - OriginalChecksum=6ff0afbe9d31f08b72159fcf24070c9f (do not edit this line) */
