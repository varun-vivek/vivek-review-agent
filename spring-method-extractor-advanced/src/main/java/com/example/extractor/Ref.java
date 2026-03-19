
package com.example.extractor;

public class Ref {
    public String className;
    public String filePath;
    public String role;

    public Ref(String className, String role, String filePath) {
        this.className = className;
        this.role = role;
        this.filePath = filePath;
    }
}
