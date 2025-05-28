package project.models;

import java.util.ArrayList;
import java.util.List;

public class MethodInstance {
    private Release release;
    private String filePath;
    private String methodName;
    private String className;
    private String signature;
    private String fullSignature;

    // ck method metrics
    private int loc;
    private int wmc;
    private int qtyAssigment;
    private int qtyMathOperations;
    private int qtyTryCatch;
    private int qtyReturn;
    private int fanin;
    private int fanout;



    // class metrics
    private int age;
    private int nAuth;
    private int nr;
    private int nSmells;

    private boolean buggy;



    // Constructors
    public MethodInstance() {
    }

    @Override
    public String toString() {
        return " sono nella method instance " + this.methodName + " con il path " + this.filePath + " e con gli smell  "+this.nSmells;
    }

    public MethodInstance(String filePath, String methodName, String signature) {
        this.filePath = filePath;
        this.methodName = methodName;
        this.signature = signature;
        this.fullSignature = filePath + "#" + methodName + signature;
    }
    /**
     * Crea una chiave univoca per il metodo utilizzando i metodi nativi di CK.
     *
     * @param method
     * @return Una chiave univoca per il metodo
     */
    public static String createMethodKey(MethodInstance method) {
        String className = method.getClassName() != null ?
                method.getClassName() : "anonymous";
        String methodName = method.getMethodName() != null ?
                method.getMethodName() : "anonymous";

        // Use full class name + method name as key
        return className + "#" + methodName;
    }

    public Release getRelease() {
        return release;
    }

    public void setRelease(Release release) {
        this.release = release;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getClassName() {

        return className;
    }

    public void setClassName(String className) {
        String expectedPath = className.replace('.', '/') + ".java";
//        expectedPath="bookkeeper-server/src/main/java/"+expectedPath;
        this.className = expectedPath;
    }

    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }

    public String getFullSignature() {
        return fullSignature;
    }

    public void setFullSignature(String fullSignature) {
        this.fullSignature = fullSignature;
    }

    public int getLoc() {
        return loc;
    }

    public void setLoc(int loc) {
        this.loc = loc;
    }

    public int getWmc() {
        return wmc;
    }

    public void setWmc(int wmc) {
        this.wmc = wmc;
    }

    public int getQtyAssigment() {
        return qtyAssigment;
    }

    public void setQtyAssigment(int qtyAssigment) {
        this.qtyAssigment = qtyAssigment;
    }

    public int getQtyMathOperations() {
        return qtyMathOperations;
    }

    public void setQtyMathOperations(int qtyMathOperations) {
        this.qtyMathOperations = qtyMathOperations;
    }

    public int getFanin() {
        return fanin;
    }

    public void setFanin(int fanin) {
        this.fanin = fanin;
    }

    public int getFanout() {
        return fanout;
    }

    public void setFanout(int fanout) {
        this.fanout = fanout;
    }

    public int getQtyTryCatch() {
        return qtyTryCatch;
    }

    public void setQtyTryCatch(int qtyTryCatch) {
        this.qtyTryCatch = qtyTryCatch;
    }

    public int getQtyReturn() {
        return qtyReturn;
    }

    public void setQtyReturn(int qtyReturn) {
        this.qtyReturn = qtyReturn;
    }

    public int getAge() {
        return age;
    }

    public void setAge(int age) {
        this.age = age;
    }

    public int getnAuth() {
        return nAuth;
    }

    public void setnAuth(int nAuth) {
        this.nAuth = nAuth;
    }


    public boolean isBuggy() {
        return buggy;
    }

    public void setBuggy(boolean buggy) {
        this.buggy = buggy;
    }

    public int getnSmells() {
        return nSmells;
    }

    public void setnSmells(int nSmells) {
        this.nSmells = nSmells;
    }


    public void setNr(int nr) {
        this.nr = nr;
    }
    public int getNr(){
        return this.nr;
    }

    public static String ckSignature(String fullSignature) {
        if (fullSignature == null || fullSignature.isEmpty()) {
            return "";
        }
        // Esempio: da "sendRead/3[java.util.ArrayList<java.net.InetSocketAddress>,...]" → "sendRead"
        int slashIndex = fullSignature.indexOf('/');
        if (slashIndex != -1) {
            return fullSignature.substring(0, slashIndex);
        }
        return fullSignature; // fallback: magari è già solo il nome
    }

}