package project.models;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

public class ClassFile {
    private String content;
    private String className;
    private String path;
    private List<MethodInstance> methods;
    private List<String> authors = new ArrayList<>();
    private int nAuth;          // n of authors
    private int age=-1;         // age of a class
    private int nr;             // number of revisions at the single file
    private Date creationDate=null;
    private int nSnmells=0;
    public ClassFile(){

        this.methods = new ArrayList<>();
    }

    public ClassFile(String content, String path) {
        this.content = content;
        this.path = path;
        this.methods = new ArrayList<>();
    }
    public static  String extractClassName(String path) {
        if (path == null) return null;

        // Rimuovi l'estensione .java se presente
        if (path.endsWith(".java")) {
            path = path.substring(0, path.length() - 5);
        }

        // Prendi l'ultima parte del percorso dopo l'ultimo punto o slash
        int lastDot = path.lastIndexOf('.');
        int lastSlash = path.lastIndexOf('/');
        int lastPos = Math.max(lastDot, lastSlash);

        if (lastPos >= 0 && lastPos < path.length() - 1) {
            return path.substring(lastPos + 1);
        }

        return path;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public String getClassName() {
        return this.className;
    }
    public static String getKey(ClassFile cl ){
        String className=  cl.className!=null?
               cl.className:"anonymous";
        String filePath = cl.getPath() != null ?
                cl.getPath() : "anonymous";
        return filePath + "#" + className;
    }
    public static String getKey(String filePath, String className){
        return filePath + "#" + className;
    }


    public void addMethod(MethodInstance method){
        if (this.methods==null){
            this.methods=new ArrayList<>();
        }
        boolean methodExists = false;
        for(MethodInstance m:this.methods){
            if(MethodInstance.createMethodKey(m).equals(MethodInstance.createMethodKey(method))){
                this.methods.remove(m);
                this.methods.add(method);
                methodExists = true;
                break;
            }
        }
        if (!methodExists) {
            this.methods.add(method);
        }
    }
    public List<MethodInstance> getMethods() {
        return methods;
    }

    public String getContent() {
        return content;
    }

    public String getPath() {
        return this.path;
    }
    public void setPath(String path) {
        this.path = path;
    }
    public void incrementNR(){

        this.nr = this.nr + 1;
    }
    public int getNR(){
        return this.nr;
    }

    public void addAuthor(String name){
        if(!this.authors.contains(name)){
            authors.add(name);
            this.nAuth = authors.size();
        }
    }

    public int getnAuth(){
        return this.nAuth;
    }

    public void setAge(int age){
        this.age = age;
    }

    public void setCreationDate(Date date){
        this.creationDate = date;
    }

    public Date getCreationDate(){
        return this.creationDate;
    }


    public int getAge(){
        return this.age;
    }

    public int getnSnmells() {
        return nSnmells;
    }

    public void setnSnmells(int nSnmells) {
        this.nSnmells = nSnmells;
    }




    /**
     * TO USE IT FOR CK RESULTS
     * */
    public static String getNameClass(String fullClassName) {
        // Gestione delle classi anonime e annidate
        int dollarIndex = fullClassName.indexOf('$');
        String cleanName;
        if (dollarIndex != -1) {
            // Controlla se dopo il $ c'è un numero o la parola "Anonymous" seguita da numeri
            String afterDollar = fullClassName.substring(dollarIndex + 1);
            if (afterDollar.matches("\\d+") || afterDollar.matches("Anonymous\\d+")){
                // È una classe anonima, rimuovi tutto dopo $
                cleanName = fullClassName.substring(0, dollarIndex);
            } else {
                // È una classe annidata normale, mantieni il nome completo
                cleanName = fullClassName;
            }
        } else {
            cleanName = fullClassName;
        }
        String[] parts = cleanName.split("\\.");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].equals("benchmark")) {
                return String.join("/", Arrays.copyOfRange(parts, i, parts.length));
            }
        }
        int lastDot = cleanName.lastIndexOf('.');
        return (lastDot != -1) ? cleanName.substring(lastDot + 1) : cleanName;
    }
    public static String extractPath(String fullClassName) {
        // Gestione delle classi anonime e annidate
        int dollarIndex = fullClassName.indexOf('$');
        String cleanName;

        if (dollarIndex != -1) {
            // Se c'è un $, prendiamo solo la parte prima del $
            cleanName = fullClassName.substring(0, dollarIndex);
        } else {
            cleanName = fullClassName;
        }

        // Converti i punti in slash e aggiungi l'estensione
        return cleanName.replace('.', '/') + ".java";
    }

}
