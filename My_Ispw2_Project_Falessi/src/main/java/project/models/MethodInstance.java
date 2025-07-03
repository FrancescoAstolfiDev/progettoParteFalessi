package project.models;

public class MethodInstance {

    private static final String ANONYMOUS="anonymous";
    private String releaseName;
    private String classPath;
    private String className;
    private String methodName;
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
        return " sono nella method instance " + this.methodName + " con il path " + this.classPath + " e con gli smell  "+this.nSmells;
    }

    public MethodInstance(String filePath, String methodName, String signature) {
        this.classPath = filePath;
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
        String releaseName= method.getReleaseName() !=null?
                method.getReleaseName():"0.0.0";
        String filePath = method.getClassPath() != null ?
                method.getClassPath() :ANONYMOUS;
        String className=method.getClassName() != null ? method.getClassName():ANONYMOUS;
        String methodName = method.getMethodName() != null ?
                method.getMethodName() : ANONYMOUS;
        // Use full class name + method name as key
        return releaseName + "#" + filePath +"#"+ className+ "#"  + methodName;
    }

    public String getReleaseName() {
        return releaseName;
    }

    public static String cleanMethodName(String methodName) {
        if (methodName == null || methodName.isEmpty()) {
            return "";
        }

        // Trova l'indice del carattere '/'
        int slashIndex = methodName.indexOf('/');

        // Se trova il carattere '/', restituisce la parte prima di esso
        if (slashIndex != -1) {
            return methodName.substring(0, slashIndex);
        }

        // Se non trova il carattere '/', restituisce il nome originale
        return methodName;
    }

    public void setReleaseName(String releaseName) {
        this.releaseName = releaseName;
    }

    public String getClassPath() {
        return classPath;
    }

    public void setClassPath(String classPath) {
        this.classPath = classPath;
    }

    public String getMethodName() {

        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
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