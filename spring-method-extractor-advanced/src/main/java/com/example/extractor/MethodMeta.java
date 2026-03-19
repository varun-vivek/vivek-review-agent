
package com.example.extractor;

import java.util.*;

public class MethodMeta {
    public String methodId;
    public String methodName;
    public String className;
    public String filePath;
    public int lineStart;
    public int lineEnd;

    public List<String> annotations = new ArrayList<>();
    public List<Call> callsInternal = new ArrayList<>();
    public List<ExternalCall> callsExternal = new ArrayList<>();
    public List<Ref> entityRefs = new ArrayList<>();
    public List<HelperRef> helperRefs = new ArrayList<>();
}
