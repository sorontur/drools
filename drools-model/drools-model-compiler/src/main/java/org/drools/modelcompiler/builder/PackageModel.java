/*
 * Copyright 2005 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.drools.modelcompiler.builder;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

import org.drools.compiler.builder.impl.KnowledgeBuilderConfigurationImpl;
import org.drools.compiler.compiler.DialectCompiletimeRegistry;
import org.drools.compiler.lang.descr.EntryPointDeclarationDescr;
import org.drools.core.definitions.InternalKnowledgePackage;
import org.drools.javaparser.JavaParser;
import org.drools.javaparser.ast.CompilationUnit;
import org.drools.javaparser.ast.Modifier;
import org.drools.javaparser.ast.NodeList;
import org.drools.javaparser.ast.body.BodyDeclaration;
import org.drools.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.drools.javaparser.ast.body.FieldDeclaration;
import org.drools.javaparser.ast.body.InitializerDeclaration;
import org.drools.javaparser.ast.body.MethodDeclaration;
import org.drools.javaparser.ast.body.VariableDeclarator;
import org.drools.javaparser.ast.comments.JavadocComment;
import org.drools.javaparser.ast.expr.ClassExpr;
import org.drools.javaparser.ast.expr.Expression;
import org.drools.javaparser.ast.expr.MethodCallExpr;
import org.drools.javaparser.ast.expr.NameExpr;
import org.drools.javaparser.ast.expr.SimpleName;
import org.drools.javaparser.ast.expr.StringLiteralExpr;
import org.drools.javaparser.ast.stmt.BlockStmt;
import org.drools.javaparser.ast.type.ClassOrInterfaceType;
import org.drools.javaparser.ast.type.Type;
import org.drools.model.Global;
import org.drools.model.Model;
import org.drools.model.Rule;
import org.drools.model.WindowReference;
import org.drools.modelcompiler.builder.generator.DRLIdGenerator;
import org.drools.modelcompiler.builder.generator.DrlxParseUtil;
import org.drools.modelcompiler.builder.generator.QueryGenerator;
import org.drools.modelcompiler.builder.generator.QueryParameter;
import org.kie.api.runtime.rule.AccumulateFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.stream.Collectors.joining;

import static org.drools.core.util.StringUtils.generateUUID;
import static org.drools.modelcompiler.builder.generator.DrlxParseUtil.toClassOrInterfaceType;
import static org.drools.modelcompiler.builder.generator.DrlxParseUtil.toVar;
import static org.drools.modelcompiler.builder.generator.DslMethodNames.GLOBAL_OF_CALL;

public class PackageModel {

    private static final Logger logger          = LoggerFactory.getLogger(PackageModel.class);

    public static final String DATE_TIME_FORMATTER_FIELD = "DATE_TIME_FORMATTER";
    public static final String STRING_TO_DATE_METHOD = "string_2_date";

    private static final String RULES_FILE_NAME = "Rules";

    private static final int RULES_PER_CLASS = 5;
    private static final int RULES_DECLARATION_PER_CLASS = 1000;

    private final String name;
    private final boolean isPattern;
    private final DialectCompiletimeRegistry dialectCompiletimeRegistry;

    private final String rulesFileName;
    
    private Set<String> imports = new HashSet<>();
    private Set<String> staticImports = new HashSet<>();
    private Set<String> entryPoints = new HashSet<>();
    private Map<String, Method> staticMethods;

    private Map<String, Class<?>> globals = new HashMap<>();

    private Map<String, MethodDeclaration> ruleMethods = new LinkedHashMap<>(); // keep rules order to obey implicit salience

    private Map<String, MethodDeclaration> queryMethods = new HashMap<>();

    private Map<String, QueryGenerator.QueryDefWithType> queryDefWithType = new HashMap<>();

    private Map<String, MethodCallExpr> windowReferences = new HashMap<>();

    private Map<String, List<QueryParameter>> queryVariables = new HashMap<>();

    private List<MethodDeclaration> functions = new ArrayList<>();

    private List<ClassOrInterfaceDeclaration> generatedPOJOs = new ArrayList<>();
    private List<GeneratedClassWithPackage> generatedAccumulateClasses = new ArrayList<>();

    private List<Expression> typeMetaDataExpressions = new ArrayList<>();

    private DRLIdGenerator exprIdGenerator;

    private KnowledgeBuilderConfigurationImpl configuration;
    private Map<String, AccumulateFunction> accumulateFunctions;
    private InternalKnowledgePackage pkg;

    public PackageModel(String name, KnowledgeBuilderConfigurationImpl configuration, boolean isPattern, DialectCompiletimeRegistry dialectCompiletimeRegistry, DRLIdGenerator exprIdGenerator) {
        this.name = name;
        this.isPattern = isPattern;
        this.rulesFileName = generateRulesFileName();
        this.configuration = configuration;
        this.exprIdGenerator = exprIdGenerator;
        this.dialectCompiletimeRegistry = dialectCompiletimeRegistry;
    }

    public String getRulesFileName() {
        return rulesFileName;
    }

    private String generateRulesFileName() {
        return RULES_FILE_NAME + generateUUID();
    }

    public KnowledgeBuilderConfigurationImpl getConfiguration() {
        return configuration;
    }

    public String getName() {
        return name;
    }
    
    public DRLIdGenerator getExprIdGenerator() {
        return exprIdGenerator;
    }

    public void addImports(Collection<String> imports) {
        this.imports.addAll(imports);
    }

    public Collection<String> getImports() {
        return this.imports;
    }

    public void addStaticImports(Collection<String> imports) {
        this.staticImports.addAll(imports);
    }

    public void addEntryPoints(Collection<EntryPointDeclarationDescr> entryPoints) {
        entryPoints.stream().map( EntryPointDeclarationDescr::getEntryPointId ).forEach( this.entryPoints::add );
    }

    public Collection<String> getStaticImports() {
        return this.staticImports;
    }

    public Method getStaticMethod(String methodName) {
        return getStaticMethods().get(methodName);
    }

    private Map<String, Method> getStaticMethods() {
        if (staticMethods == null) {
            staticMethods = new HashMap<>();
            for (String i : staticImports) {
                if (i.endsWith( ".*" )) {
                    String className = i.substring( 0, i.length()-2 );
                    try {
                        Class<?> importedClass = pkg.getTypeResolver().resolveType( className );
                        for (Method m : importedClass.getMethods()) {
                            if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                                staticMethods.put(m.getName(), m);
                            }
                        }
                    } catch (ClassNotFoundException e1) {
                        throw new UnsupportedOperationException("Class not found", e1);
                    }
                } else {
                    int splitPoint = i.lastIndexOf( '.' );
                    String className = i.substring( 0, splitPoint );
                    String methodName = i.substring( splitPoint+1 );
                    try {
                        Class<?> importedClass = pkg.getTypeResolver().resolveType( className );
                        for (Method m : importedClass.getMethods()) {
                            if (java.lang.reflect.Modifier.isStatic(m.getModifiers()) && m.getName().equals( methodName )) {
                                staticMethods.put(methodName, m);
                                break;
                            }
                        }
                    } catch (ClassNotFoundException e1) {
                        throw new UnsupportedOperationException("Class not found", e1);
                    }
                }
            }
        }
        return staticMethods;
    }

    public void addGlobals(Map<String, String> values) {
        Map<String, Class<?>> transformed;
        transformed = values
                .entrySet()
                .stream()
                .collect(Collectors.toMap( Entry::getKey, e -> {
                    try {
                        return Class.forName(e.getValue(), true, configuration.getClassLoader());
                    } catch (ClassNotFoundException e1) {
                        throw new UnsupportedOperationException("Class not found", e1);
                    }
                }));
        globals.putAll(transformed);
    }

    public Map<String, Class<?>> getGlobals() {
        return globals;
    }

    public void addTypeMetaDataExpressions(Expression typeMetaDataExpression) {
        typeMetaDataExpressions.add(typeMetaDataExpression);
    }

    public void putRuleMethod(String methodName, MethodDeclaration ruleMethod) {
        this.ruleMethods.put(methodName, ruleMethod);
    }

    public void putQueryMethod(MethodDeclaration queryMethod) {
        this.queryMethods.put(queryMethod.getNameAsString(), queryMethod);
    }

    public MethodDeclaration getQueryMethod(String key) {
        return queryMethods.get(key);
    }

    public void putQueryVariable(String queryName, QueryParameter qp) {
        this.queryVariables.computeIfAbsent(queryName, k -> new ArrayList<>());
        this.queryVariables.get(queryName).add(qp);
    }

    public List<QueryParameter> queryVariables(String queryName) {
        return this.queryVariables.get(queryName);
    }

    public Map<String, QueryGenerator.QueryDefWithType> getQueryDefWithType() {
        return queryDefWithType;
    }

    public void addAllFunctions(List<MethodDeclaration> functions) {
        this.functions.addAll(functions);
    }

    public void addGeneratedPOJO(ClassOrInterfaceDeclaration pojo) {
        this.generatedPOJOs.add(pojo);
    }

    public List<ClassOrInterfaceDeclaration> getGeneratedPOJOsSource() {
        return generatedPOJOs;
    }

    public void addGeneratedAccumulateClasses(GeneratedClassWithPackage clazz) {
        this.generatedAccumulateClasses.add(clazz);
    }

    public List<GeneratedClassWithPackage> getGeneratedAccumulateClasses() {
        return generatedAccumulateClasses;
    }

    public void addAllWindowReferences(String methodName, MethodCallExpr windowMethod) {
        this.windowReferences.put(methodName, windowMethod);
    }

    public Map<String, MethodCallExpr> getWindowReferences() {
        return windowReferences;
    }

    final static Type WINDOW_REFERENCE_TYPE = JavaParser.parseType(WindowReference.class.getCanonicalName());

    public List<MethodDeclaration> getFunctions() {
        return functions;
    }

    public Map<String, AccumulateFunction> getAccumulateFunctions() {
        return accumulateFunctions;
    }

    public void setInternalKnowledgePackage(InternalKnowledgePackage pkg) {
        this.pkg = pkg;
    }

    public InternalKnowledgePackage getPkg() {
        return pkg;
    }


    public DialectCompiletimeRegistry getDialectCompiletimeRegistry() {
        return dialectCompiletimeRegistry;
    }

    public static class RuleSourceResult {

        private final CompilationUnit mainRuleClass;
        private Collection<CompilationUnit> splitted = new ArrayList<>();

        public RuleSourceResult(CompilationUnit mainRuleClass) {
            this.mainRuleClass = mainRuleClass;
        }

        public CompilationUnit getMainRuleClass() {
            return mainRuleClass;
        }

        /**
         * Append additional class to source results.
         * @param additionalCU 
         */
        public RuleSourceResult with(CompilationUnit additionalCU) {
            splitted.add(additionalCU);
            return this;
        }

        public Collection<CompilationUnit> getSplitted() {
            return Collections.unmodifiableCollection(splitted);
        }

    }

    public RuleSourceResult getRulesSource() {
        CompilationUnit cu = new CompilationUnit();
        cu.setPackageDeclaration( name );

        manageImportForCompilationUnit(cu);
        
        ClassOrInterfaceDeclaration rulesClass = cu.addClass(rulesFileName);
        rulesClass.addImplementedType(Model.class);

        BodyDeclaration<?> dateFormatter = JavaParser.parseBodyDeclaration(
                "public final static DateTimeFormatter " + DATE_TIME_FORMATTER_FIELD + " = DateTimeFormatter.ofPattern(DateUtils.getDateFormatMask());\n");
        rulesClass.addMember(dateFormatter);

        BodyDeclaration<?> string2dateMethodMethod = JavaParser.parseBodyDeclaration(
                "    @Override\n" +
                "        public String getName() {\n" +
                "        return \"" + name + "\";\n" +
                "    }\n"
                );
        rulesClass.addMember(string2dateMethodMethod);

        BodyDeclaration<?> getNameMethod = JavaParser.parseBodyDeclaration(
                "    public static Date " + STRING_TO_DATE_METHOD + "(String s) {\n" +
                "        return GregorianCalendar.from(LocalDate.parse(s, DATE_TIME_FORMATTER).atStartOfDay(ZoneId.systemDefault())).getTime();\n" +
                "    }\n"
                );
        rulesClass.addMember(getNameMethod);

        BodyDeclaration<?> getRulesMethod = JavaParser.parseBodyDeclaration(
                "    @Override\n" +
                "    public List<org.drools.model.Rule> getRules() {\n" +
                "        return rules;\n" +
                "    }\n"
                );
        rulesClass.addMember(getRulesMethod);

        String entryPointsBuilder = entryPoints.isEmpty() ?
                "Collections.emptyList()" :
                "Arrays.asList(D.entryPoint(\"" + entryPoints.stream().collect( joining("\"), D.entryPoint(\"") ) + "\"))";

        BodyDeclaration<?> getEntryPointsMethod = JavaParser.parseBodyDeclaration(
                "    @Override\n" +
                "    public List<org.drools.model.EntryPoint> getEntryPoints() {\n" +
                "        return " + entryPointsBuilder + ";\n" +
                "    }\n"
                );
        rulesClass.addMember(getEntryPointsMethod);

        StringBuilder sb = new StringBuilder("\n");
        sb.append("With the following expression ID:\n");
        sb.append(exprIdGenerator.toString());
        sb.append("\n");
        JavadocComment exprIdComment = new JavadocComment(sb.toString());
        getRulesMethod.setComment(exprIdComment);

        BodyDeclaration<?> getGlobalsMethod = JavaParser.parseBodyDeclaration(
                "    @Override\n" +
                "    public List<org.drools.model.Global> getGlobals() {\n" +
                "        return globals;\n" +
                "    }\n");
        rulesClass.addMember(getGlobalsMethod);

        BodyDeclaration<?> getQueriesMethod = JavaParser.parseBodyDeclaration(
                "    @Override\n" +
                "    public List<org.drools.model.Query> getQueries() {\n" +
                "        return queries;\n" +
                "    }\n");
        rulesClass.addMember(getQueriesMethod);

        BodyDeclaration<?> getTypeMetaDataMethod = JavaParser.parseBodyDeclaration(
                "    @Override\n" +
                "    public List<org.drools.model.TypeMetaData> getTypeMetaDatas() {\n" +
                "        return typeMetaDatas;\n" +
                "    }\n");
        rulesClass.addMember(getTypeMetaDataMethod);

        // end of fixed part


        for(Map.Entry<String, MethodCallExpr> windowReference : windowReferences.entrySet()) {
            FieldDeclaration f = rulesClass.addField(WINDOW_REFERENCE_TYPE, windowReference.getKey(), Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);
            f.getVariables().get(0).setInitializer(windowReference.getValue());
        }

        for ( Map.Entry<String, Class<?>> g : getGlobals().entrySet() ) {
            addGlobalField(rulesClass, getName(), g.getKey(), g.getValue());
        }

        for(Map.Entry<String, QueryGenerator.QueryDefWithType> queryDef: queryDefWithType.entrySet()) {
            FieldDeclaration field = rulesClass.addField(queryDef.getValue().getQueryType(), queryDef.getKey(), Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);
            field.getVariables().get(0).setInitializer(queryDef.getValue().getMethodCallExpr());
        }

        for(Map.Entry<String, MethodDeclaration> methodName: queryMethods.entrySet()) {
            FieldDeclaration field = rulesClass.addField(methodName.getValue().getType(), methodName.getKey(), Modifier.FINAL);
            field.getVariables().get(0).setInitializer(new MethodCallExpr(null, methodName.getKey()));
        }

        // instance initializer block.
        // add to `rules` list the result of invoking each method for rule
        InitializerDeclaration rulesListInitializer = new InitializerDeclaration();
        BlockStmt rulesListInitializerBody = new BlockStmt();
        rulesListInitializer.setBody(rulesListInitializerBody);

        queryMethods.values().forEach(rulesClass::addMember);
        buildArtifactsDeclaration( queryMethods.keySet(), rulesClass, rulesListInitializerBody, "org.drools.model.Query", "queries", false );
        buildArtifactsDeclaration( getGlobals().keySet(), rulesClass, rulesListInitializerBody, "org.drools.model.Global", "globals", true );

        if ( !typeMetaDataExpressions.isEmpty() ) {
            BodyDeclaration<?> typeMetaDatasList = JavaParser.parseBodyDeclaration("List<org.drools.model.TypeMetaData> typeMetaDatas = new ArrayList<>();");
            rulesClass.addMember(typeMetaDatasList);
            for (Expression expr : typeMetaDataExpressions) {
                addInitStatement( rulesListInitializerBody, expr, "typeMetaDatas" );
            }
        } else {
            BodyDeclaration<?> typeMetaDatasList = JavaParser.parseBodyDeclaration("List<org.drools.model.TypeMetaData> typeMetaDatas = Collections.emptyList();");
            rulesClass.addMember(typeMetaDatasList);
        }

        functions.forEach(rulesClass::addMember);

        RuleSourceResult results = new RuleSourceResult(cu);

        int ruleCount = ruleMethods.size();
        boolean requiresMultipleRulesLists = ruleCount >= RULES_DECLARATION_PER_CLASS-1;

        MethodCallExpr rules = buildRulesField( rulesClass );
        if (requiresMultipleRulesLists) {
            addRulesList( rulesListInitializerBody, "rulesList" );
        }

        // each method per Drlx parser result
        int count = -1;
        Map<Integer, ClassOrInterfaceDeclaration> splitted = new LinkedHashMap<>();
        for (Entry<String, MethodDeclaration> ruleMethodKV : ruleMethods.entrySet()) {
            ClassOrInterfaceDeclaration rulesMethodClass = splitted.computeIfAbsent(++count / RULES_PER_CLASS, i -> {
                CompilationUnit cuRulesMethod = new CompilationUnit();
                results.with(cuRulesMethod);
                cuRulesMethod.setPackageDeclaration(name);
                manageImportForCompilationUnit(cuRulesMethod);
                cuRulesMethod.addImport(name + "." + rulesFileName, true, true);
                String currentRulesMethodClassName = rulesFileName + "RuleMethods" + i;
                return cuRulesMethod.addClass(currentRulesMethodClassName);
            });
            rulesMethodClass.addMember(ruleMethodKV.getValue());

            if (count % RULES_DECLARATION_PER_CLASS == RULES_DECLARATION_PER_CLASS-1) {
                int index = count / RULES_DECLARATION_PER_CLASS;
                rules = buildRulesField(results, index);
                addRulesList( rulesListInitializerBody, rulesFileName + "Rules" + index + ".rulesList" );
            }

            // manage in main class init block:
            rules.addArgument(new MethodCallExpr(new NameExpr(rulesMethodClass.getNameAsString()), ruleMethodKV.getKey()));
        }

        BodyDeclaration<?> rulesList = requiresMultipleRulesLists ?
                JavaParser.parseBodyDeclaration("List<org.drools.model.Rule> rules = new ArrayList<>(" + ruleCount + ");") :
                JavaParser.parseBodyDeclaration("List<org.drools.model.Rule> rules = rulesList;");
        rulesClass.addMember(rulesList);

        if (!rulesListInitializer.getBody().getStatements().isEmpty()) {
            rulesClass.addMember( rulesListInitializer );
        }

        return results;
    }

    private void buildArtifactsDeclaration( Collection<String> artifacts, ClassOrInterfaceDeclaration rulesClass, BlockStmt rulesListInitializerBody, String type, String fieldName, boolean needsToVar ) {
        if (!artifacts.isEmpty()) {
            BodyDeclaration<?> queriesList = JavaParser.parseBodyDeclaration("List<" + type + "> " + fieldName + " = new ArrayList<>();");
            rulesClass.addMember(queriesList);
            for (String name : artifacts) {
                addInitStatement( rulesListInitializerBody, new NameExpr( needsToVar ? toVar(name) : name ), fieldName );
            }
        } else {
            BodyDeclaration<?> queriesList = JavaParser.parseBodyDeclaration("List<" + type + "> " + fieldName + " = Collections.emptyList();");
            rulesClass.addMember(queriesList);
        }
    }

    private void addInitStatement( BlockStmt rulesListInitializerBody, Expression expr, String fieldName ) {
        NameExpr rulesFieldName = new NameExpr( fieldName );
        MethodCallExpr add = new MethodCallExpr( rulesFieldName, "add" );
        add.addArgument( expr );
        rulesListInitializerBody.addStatement( add );
    }

    private void addRulesList( BlockStmt rulesListInitializerBody, String listName ) {
        MethodCallExpr add = new MethodCallExpr(new NameExpr("rules"), "addAll");
        add.addArgument(listName);
        rulesListInitializerBody.addStatement(add);
    }

    private MethodCallExpr buildRulesField(RuleSourceResult results, int index) {
        CompilationUnit cu = new CompilationUnit();
        results.with(cu);
        cu.setPackageDeclaration(name);
        cu.addImport(Arrays.class.getCanonicalName());
        cu.addImport(List.class.getCanonicalName());
        cu.addImport(Rule.class.getCanonicalName());
        String currentRulesMethodClassName = rulesFileName + "Rules" + index;
        ClassOrInterfaceDeclaration rulesClass = cu.addClass(currentRulesMethodClassName);
        return buildRulesField( rulesClass );
    }

    private MethodCallExpr buildRulesField( ClassOrInterfaceDeclaration rulesClass ) {
        MethodCallExpr rulesInit = new MethodCallExpr( null, "Arrays.asList" );
        ClassOrInterfaceType rulesType = new ClassOrInterfaceType(null, new SimpleName("List"), new NodeList<Type>(new ClassOrInterfaceType(null, "Rule")));
        VariableDeclarator rulesVar = new VariableDeclarator( rulesType, "rulesList", rulesInit );
        rulesClass.addMember( new FieldDeclaration( EnumSet.of( Modifier.PUBLIC, Modifier.STATIC), rulesVar ) );
        return rulesInit;
    }

    private void manageImportForCompilationUnit(CompilationUnit cu) {
        // fixed part
        cu.addImport("java.util.*");
        cu.addImport("org.drools.model.*");
        if(isPattern) {
            cu.addImport("org.drools.modelcompiler.dsl.pattern.D");
        } else {
            cu.addImport("org.drools.modelcompiler.dsl.flow.D");
        }
        cu.addImport("org.drools.model.Index.ConstraintType");
        cu.addImport("java.time.*");
        cu.addImport("java.time.format.*");
        cu.addImport("java.text.*");
        cu.addImport("org.drools.core.util.*");

        // imports from DRL:
        for ( String i : imports ) {
            if ( i.equals(name+".*") ) {
                continue; // skip same-package star import.
            }
            cu.addImport(i);
        }
        for (String i : staticImports) {
            cu.addImport( i, true, false );
        }
    }

    private static void addGlobalField(ClassOrInterfaceDeclaration classDeclaration, String packageName, String globalName, Class<?> globalClass) {
        ClassOrInterfaceType varType = toClassOrInterfaceType(Global.class);
        varType.setTypeArguments(DrlxParseUtil.classToReferenceType(globalClass));
        Type declType = DrlxParseUtil.classToReferenceType(globalClass);

        MethodCallExpr declarationOfCall = new MethodCallExpr(null, GLOBAL_OF_CALL);
        declarationOfCall.addArgument(new ClassExpr(declType ));
        declarationOfCall.addArgument(new StringLiteralExpr(packageName));
        declarationOfCall.addArgument(new StringLiteralExpr(globalName));

        FieldDeclaration field = classDeclaration.addField(varType, toVar(globalName), Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);

        field.getVariables().get(0).setInitializer(declarationOfCall);
    }

    public void logRule(String source) {
        if ( logger.isDebugEnabled() ) {
            logger.debug( "=====" );
            logger.debug( source );
            logger.debug( "=====" );
        }
    }

    public void addAccumulateFunctions(Map<String, AccumulateFunction> accumulateFunctions) {
        this.accumulateFunctions = accumulateFunctions;
    }

    public boolean hasDeclaration(String id) {
        return globals.get(id) != null;
    }
}
