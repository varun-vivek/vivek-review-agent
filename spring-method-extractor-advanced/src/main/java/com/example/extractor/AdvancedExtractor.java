
package com.example.extractor;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedParameterDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserClassDeclaration;

import java.io.File;
import java.util.*;

public class AdvancedExtractor {

    public static MethodMeta extract(MethodDeclaration method, CompilationUnit cu, Map<String, String> classToFilePath, Map<String, String> configMap) {

        MethodMeta meta = new MethodMeta();

        String className = method.findAncestor(ClassOrInterfaceDeclaration.class)
                .map(c -> c.getNameAsString()).orElse("Unknown");

        String packageName = cu.getPackageDeclaration()
                .map(p -> p.getNameAsString()).orElse("");

        meta.className = className;
        meta.methodName = method.getNameAsString();
        meta.methodId = packageName + "." + className + "#" + buildSignature(method);
        meta.filePath = cu.getStorage().map(s -> s.getPath().toString()).orElse("");
        meta.entityRefs = extractRefs(method, classToFilePath);

        method.getRange().ifPresent(r -> {
            meta.lineStart = r.begin.line;
            meta.lineEnd = r.end.line;
        });

        method.getAnnotations().forEach(a -> meta.annotations.add(a.toString()));

        // Internal calls
        method.findAll(MethodCallExpr.class).forEach(call -> {

            Call c = new Call();
            c.expr = call.toString();
            // ✅ STEP 1: Detect external REST calls FIRST
            if (isExternalHttpCall(call)) {

                ExternalCall ex = new ExternalCall();
                ex.callType = "RestTemplate";

                String callStrs = call.toString();

                // ✅ 1. HTTP METHOD
                ex.httpMethod = extractHttpMethod(callStrs);

                // ✅ 2. URL expression (first argument)
                String urlExpr = call.getArguments().size() > 0
                        ? call.getArgument(0).toString()
                        : "UNKNOWN";

                // ✅ 3. Extract path + base (best effort)
                String resolvedUrl = resolveUrlFromMethod(method, urlExpr);

                if (resolvedUrl != null && resolvedUrl.contains("+")) {
                    // Case: base + "/path"
                    String[] parts = resolvedUrl.split("\\+", 2);

                    ex.resolvedBaseUrl = clean(parts[0]);
                    ex.path = clean(parts[1]);

                } else if (resolvedUrl != null) {
                    ex.path = clean(resolvedUrl);
                    ex.resolvedBaseUrl = "UNKNOWN";
                } else {
                    ex.path = urlExpr;
                    ex.resolvedBaseUrl = "UNKNOWN";
                }

                // ✅ 4. Property key (if @Value used)
                resolveProperty(ex, method, ex.resolvedBaseUrl, configMap);

                // ✅ 5. unresolved flag
                ex.unresolved = "UNKNOWN".equals(ex.resolvedBaseUrl);

                meta.callsExternal.add(ex);

                return; // ❗ VERY IMPORTANT: stop here (do not go to internal)
            }

            // ✅ STEP 2: Normal internal resolution
            try {
                var resolved = call.resolve();
                var typeDecl = resolved.declaringType();

                if (typeDecl instanceof com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserClassDeclaration jp) {

                    String classNamee = typeDecl.getClassName();
                    String packageNamee = typeDecl.getPackageName();

                    c.targetClassName = classNamee;

                    String signature = java.util.stream.IntStream
                            .range(0, resolved.getNumberOfParams())
                            .mapToObj(i -> resolved.getParam(i).getType().describe())
                            .collect(java.util.stream.Collectors.joining(","));

                    c.resolvedMethodId =
                            packageNamee + "." + classNamee + "#" +
                                    resolved.getName() + "(" + signature + ")";

                    c.targetFilePath = jp.getWrappedNode()
                            .findCompilationUnit()
                            .flatMap(cuu -> cuu.getStorage())
                            .map(s -> s.getPath().toString())
                            .orElse("UNKNOWN");

                    meta.callsInternal.add(c);
                    return;
                }

            } catch (Exception e) {
                // ❗ FALLBACK BELOW
            }

            // 🔥 FALLBACK LOGIC (IMPORTANT)
            call.getScope().ifPresent(scope -> {

                String scopeName = scope.toString(); // intentService

                method.findAncestor(ClassOrInterfaceDeclaration.class)
                        .ifPresent(clazz -> {

                            clazz.getFields().forEach(field -> {
                                field.getVariables().forEach(var -> {

                                    if (var.getNameAsString().equals(scopeName)) {

                                        String classNamee = var.getType().asString();

                                        c.targetClassName = classNamee;
                                        c.resolvedMethodId =
                                                classNamee + "#" + call.getNameAsString() + "(UNKNOWN)";

                                        // 🔥 FIX HERE
                                        c.targetFilePath =
                                                classToFilePath.getOrDefault(classNamee, "UNKNOWN");

                                        meta.callsInternal.add(c);
                                    }
                                });
                            });
                        });
            });

        });

        return meta;
    }

    private static String getFilePath(ResolvedReferenceTypeDeclaration decl) {
        try {
            if (decl instanceof JavaParserClassDeclaration jpDecl) {
                return jpDecl.getWrappedNode()
                        .findCompilationUnit()
                        .flatMap(cu -> cu.getStorage())
                        .map(s -> s.getPath().toString())
                        .orElse(null);
            }
        } catch (Exception ignored) {}

        return null;
    }

    private static String getRole(ResolvedReferenceTypeDeclaration decl) {

        // ENUM
        if (decl.isEnum()) return "enum";

        try {
            if (decl instanceof JavaParserClassDeclaration jpDecl) {
                var node = jpDecl.getWrappedNode();

                // ---- ENTITY CHECK ----
                boolean isEntity = node.getAnnotations().stream()
                        .anyMatch(a -> a.getNameAsString().equals("Entity"));

                if (isEntity) return "entity";

                // ---- LOMBOK CHECK ----
                boolean hasLombok = node.getAnnotations().stream()
                        .anyMatch(a -> {
                            String n = a.getNameAsString();
                            return n.equals("Getter") || n.equals("Setter") || n.equals("Data");
                        });

                if (hasLombok) return "dto";

                // ---- PLAIN POJO CHECK ----
                if (isPlainPojo(node)) {
                    return "dto";
                }

                // ---- CONSTRUCTOR POJO ---- (NEW)
                if (isConstructorPojo(node)) return "dto";
            }
        } catch (Exception ignored) {}

        return null;
    }

    private static void resolveResolvedType(ResolvedType resolvedType, List<Ref> refs, Set<String> visited) {
        if (!resolvedType.isReferenceType()) return;

        try {
            ResolvedReferenceType refType = resolvedType.asReferenceType();
            String qName = refType.getQualifiedName();

            if (!visited.add(qName)) return;

            Optional<ResolvedReferenceTypeDeclaration> declOpt = refType.getTypeDeclaration();
            if (declOpt.isEmpty()) return;

            ResolvedReferenceTypeDeclaration decl = declOpt.get();

            String role = getRole(decl);
            if (role == null) return;

            String className = decl.getName();
            String filePath = getFilePath(decl);

            refs.add(new Ref(className, role, filePath));

        } catch (Exception ignored) {}
    }

    private static void resolveType(Type type, List<Ref> refs, Set<String> visited, Map<String, String> configMap) {
        try {
            ResolvedType resolvedType = type.resolve();
            resolveResolvedType(resolvedType, refs, visited);
        } catch (UnsolvedSymbolException e) {
            // fallback
            fallbackHandle(type, refs, visited, configMap);
        }
    }

    private static void fallbackHandle(Type type,
                                       List<Ref> refs,
                                       Set<String> visited,
                                       Map<String, String> classPathMap) {

        String raw = type.asString(); // e.g. List<UserDto>

        // extract all possible class names (handle generics)
        List<String> classNames = extractClassNames(raw);

        for (String name : classNames) {

            if (!visited.add(name)) continue;

            String filePath = classPathMap.get(name);

            String role = "unknown";

            if (filePath != null) {
                role = detectRoleFromFile(filePath); // 🔥 key improvement
            }

            refs.add(new Ref(name, role, filePath));
        }
    }

    private static String detectRoleFromFile(String filePath) {

        try {
            CompilationUnit cu = StaticJavaParser.parse(new File(filePath));

            Optional<ClassOrInterfaceDeclaration> clazz =
                    cu.findFirst(ClassOrInterfaceDeclaration.class);

            if (clazz.isEmpty()) return "unknown";

            ClassOrInterfaceDeclaration node = clazz.get();

            // ENTITY
            if (hasAnnotation(node, "Entity")) return "entity";

            // ENUM
            if (cu.findFirst(EnumDeclaration.class).isPresent()) return "enum";

            // LOMBOK
            if (hasAnyAnnotation(node, List.of("Getter","Setter","Data","Builder","Value"))) {
                return "dto";
            }

            // PLAIN POJO
            if (isPlainPojo(node)) return "dto";

            // CONSTRUCTOR POJO
            if (isConstructorPojo(node)) return "dto";

        } catch (Exception ignored) {}

        return "unknown";
    }

    private static List<String> extractClassNames(String typeStr) {

        // Remove generics symbols
        typeStr = typeStr.replaceAll("[<>]", ",");

        String[] parts = typeStr.split(",");

        List<String> result = new ArrayList<>();

        for (String part : parts) {
            part = part.trim();

            // remove package if present
            if (part.contains(".")) {
                part = part.substring(part.lastIndexOf('.') + 1);
            }

            if (!part.isEmpty() && Character.isUpperCase(part.charAt(0))) {
                result.add(part);
            }
        }

        return result;
    }

    private static boolean hasAnnotation(ClassOrInterfaceDeclaration node, String name) {
        return node.getAnnotations().stream()
                .anyMatch(a -> a.getNameAsString().equals(name));
    }

    private static boolean hasAnyAnnotation(ClassOrInterfaceDeclaration node, List<String> names) {
        return node.getAnnotations().stream()
                .anyMatch(a -> names.contains(a.getNameAsString()));
    }

    private static boolean isSpringBean(ClassOrInterfaceDeclaration clazz) {
        return clazz.getAnnotations().stream().anyMatch(a -> {
            String n = a.getNameAsString();
            return n.equals("Service") || n.equals("Component")
                    || n.equals("Repository") || n.equals("Controller");
        });
    }

    private static boolean isConstructorPojo(ClassOrInterfaceDeclaration clazz) {

        // ignore interfaces / abstract / spring beans
        if (clazz.isInterface() || clazz.isAbstract()) return false;

        if (isSpringBean(clazz)) return false;

        // must have fields
        if (clazz.getFields().isEmpty()) return false;

        // check constructors
        for (ConstructorDeclaration ctor : clazz.getConstructors()) {

            if (ctor.getParameters().isEmpty()) continue;

            // strong signal: assigning fields
            boolean assignsField = ctor.findAll(AssignExpr.class).stream()
                    .anyMatch(assign -> assign.getTarget().toString().startsWith("this."));

            if (assignsField) return true;

            // fallback: param count matches field count (common immutable pattern)
            int fieldCount = clazz.getFields().stream()
                    .mapToInt(f -> f.getVariables().size())
                    .sum();

            if (ctor.getParameters().size() == fieldCount) return true;
        }

        return false;
    }

    private static boolean isPlainPojo(ClassOrInterfaceDeclaration clazz) {

        // Must have fields
        boolean hasFields = !clazz.getFields().isEmpty();
        if (!hasFields) return false;

        boolean hasGetter = false;
        boolean hasSetter = false;

        for (MethodDeclaration method : clazz.getMethods()) {

            String name = method.getNameAsString();

            // getter: getX() or isX()
            if ((name.startsWith("get") && method.getParameters().isEmpty())
                    || (name.startsWith("is") && method.getParameters().isEmpty())) {
                hasGetter = true;
            }

            // setter: setX(...)
            if (name.startsWith("set") && method.getParameters().size() == 1) {
                hasSetter = true;
            }

            if (hasGetter && hasSetter) return true;
        }

        return false;
    }

    public static List<Ref> extractRefs(MethodDeclaration method, Map<String, String> configMap) {
        Set<String> visited = new HashSet<>();
        List<Ref> refs = new ArrayList<>();

        // 1. Parameters
        method.getParameters().forEach(p -> {
            resolveType(p.getType(), refs, visited, configMap);
        });

        // 2. Return type
        resolveType(method.getType(), refs, visited, configMap);

        // 3. Variable declarations
        method.findAll(VariableDeclarator.class).forEach(v -> {
            resolveType(v.getType(), refs, visited, configMap);
        });

        // 4. Object creation (new User())
        method.findAll(ObjectCreationExpr.class).forEach(o -> {
            resolveType(o.getType(), refs, visited, configMap);
        });

        // 5. Method calls (important for deep refs)
        method.findAll(MethodCallExpr.class).forEach(call -> {
            try {
                ResolvedMethodDeclaration resolved = call.resolve();

                // Return type
                resolveResolvedType(resolved.getReturnType(), refs, visited);

                // Parameters
                for (int i = 0; i < resolved.getNumberOfParams(); i++) {
                    ResolvedParameterDeclaration param = resolved.getParam(i);
                    resolveResolvedType(param.getType(), refs, visited);
                }

            } catch (Exception e) {
                // ignore unresolved symbols
            }
        });

        return refs;
    }

    private static String clean(String str) {
        return str.replace("\"", "").trim();
    }

    private static void resolveProperty(ExternalCall ex,
                                        MethodDeclaration method,
                                        String baseVar,
                                        Map<String, String> configMap) {

        method.findAncestor(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)
                .ifPresent(clazz -> {

                    clazz.getFields().forEach(field -> {

                        field.getVariables().forEach(var -> {

                            if (var.getNameAsString().equals(baseVar)) {

                                field.getAnnotations().forEach(a -> {

                                    if (a.getNameAsString().equals("Value")) {

                                        String raw = a.toString(); // @Value("${gitlab.base-url}")

                                        String key = raw.replaceAll(".*\\$\\{(.+)}.*", "$1");

                                        ex.propertyKey = key;

                                        // 🔥 Resolve actual value
                                        ex.resolvedBaseUrl =
                                                configMap.getOrDefault(key, "UNKNOWN");
                                    }
                                });
                            }
                        });
                    });
                });
    }

    private static String resolveUrlFromMethod(MethodDeclaration method, String varName) {

        // Case: direct string
        if (varName.startsWith("\"")) {
            return varName;
        }

        // Case: variable defined in method
        return method.findAll(com.github.javaparser.ast.body.VariableDeclarator.class)
                .stream()
                .filter(v -> v.getNameAsString().equals(varName))
                .findFirst()
                .flatMap(v -> v.getInitializer())
                .map(Object::toString)
                .orElse(null);
    }

    private static String extractHttpMethod(String callStr) {
        if (callStr.contains("HttpMethod.GET")) return "GET";
        if (callStr.contains("HttpMethod.POST")) return "POST";
        if (callStr.contains("HttpMethod.PUT")) return "PUT";
        if (callStr.contains("HttpMethod.DELETE")) return "DELETE";
        return "UNKNOWN";
    }

    private static final Set<String> HTTP_CLIENT_SIMPLE_NAMES = Set.of(
            "RestTemplate", "RestClient", "WebClient"
    );

    private static final Set<String> HTTP_METHOD_NAMES = Set.of(
            "getForObject", "getForEntity", "postForObject", "postForEntity",
            "put", "delete", "exchange", "execute", "patchForObject",
            "get", "post", "patch", "head", "options", "method", "retrieve"
    );

    public static boolean isExternalHttpCall(MethodCallExpr call) {
        if (call.getScope().isEmpty()) return false;
        if (!HTTP_METHOD_NAMES.contains(call.getNameAsString())) return false;

        Expression scope = call.getScope().get();
        if (!(scope instanceof NameExpr nameExpr)) return false;

        String varName = nameExpr.getNameAsString();

        // Walk enclosing class for field: private RestTemplate rest;
        Optional<ClassOrInterfaceDeclaration> enclosingClass =
                call.findAncestor(ClassOrInterfaceDeclaration.class);

        if (enclosingClass.isPresent()) {
            // Check fields
            boolean isClientField = enclosingClass.get().getFields().stream()
                    .filter(f -> HTTP_CLIENT_SIMPLE_NAMES.contains(f.getElementType().asString()))
                    .flatMap(f -> f.getVariables().stream())
                    .anyMatch(v -> v.getNameAsString().equals(varName));

            if (isClientField) return true;

            // Check method-local variables
            boolean isClientLocal = call.findAncestor(MethodDeclaration.class)
                    .map(method -> method.findAll(VariableDeclarator.class).stream()
                            .filter(v -> HTTP_CLIENT_SIMPLE_NAMES.contains(v.getType().asString()))
                            .anyMatch(v -> v.getNameAsString().equals(varName)))
                    .orElse(false);

            if (isClientLocal) return true;
        }

        return false;
    }

    private static boolean isRestCall(MethodCallExpr call) {

        try {
            String name = call.getNameAsString();
            if(name.equals("exchange")) {
                System.out.println("in");
            }
            var resolved = call.resolve();
            var typeDecl = resolved.declaringType();
            if(resolved.getName().equals("exchange")) {

                String className = typeDecl.getClassName();
                System.out.println(className);
            }
            String className = typeDecl.getClassName();
            String packageName = typeDecl.getPackageName();

            // ✅ Only consider HTTP clients
            boolean isHttpClient =
                    className.equals("RestTemplate") ||
                            className.equals("WebClient") ||
                            className.equals("RestClient");

            if (!isHttpClient) return false;

            // ✅ Now check method name
            String method = resolved.getName();

            return method.equals("exchange") ||
                    method.equals("getForObject") ||
                    method.equals("postForObject") ||
                    method.equals("put") ||
                    method.equals("delete");

        } catch (Exception e) {
            return false; // ❗ don't assume external if unresolved
        }
    }

    private static ExternalCall buildExternalCall(MethodCallExpr call, MethodDeclaration method) {

        ExternalCall ex = new ExternalCall();

        ex.callType = "RestTemplate";
        ex.unresolved = false;

        // 🔥 Extract HTTP method
        ex.httpMethod = call.toString().contains("HttpMethod.GET") ? "GET" : "UNKNOWN";

        // 🔥 Extract URL variable (first argument)
        if (call.getArguments().size() > 0) {
            String urlVar = call.getArgument(0).toString();

            // Try resolve variable value
            ex.path = resolveUrlValue(method, urlVar);
        }

        // 🔥 Resolve base URL (from field)
        ex.resolvedBaseUrl = resolveBaseUrl(method);

        return ex;
    }

    private static String resolveUrlValue(MethodDeclaration method, String varName) {

        return method.findAll(com.github.javaparser.ast.body.VariableDeclarator.class)
                .stream()
                .filter(v -> v.getNameAsString().equals(varName))
                .findFirst()
                .map(v -> v.getInitializer().map(Object::toString).orElse("UNKNOWN"))
                .orElse("UNKNOWN");
    }

    private static String resolveBaseUrl(MethodDeclaration method) {

        return method.findAncestor(ClassOrInterfaceDeclaration.class)
                .flatMap(clazz ->
                        clazz.getFields().stream()
                                .flatMap(f -> f.getVariables().stream())
                                .filter(v -> v.getNameAsString().toLowerCase().contains("base"))
                                .findFirst()
                )
                .map(v -> v.getNameAsString())
                .orElse("UNKNOWN");
    }

    private static String buildSignature(MethodDeclaration m) {
        List<String> types = new ArrayList<>();
        m.getParameters().forEach(p -> types.add(p.getType().asString()));
        return m.getNameAsString() + "(" + String.join(",", types) + ")";
    }
}
