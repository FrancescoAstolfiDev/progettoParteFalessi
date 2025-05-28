package project.models;


import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class ClassFile {
    private String content;
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




    public void addMethod(MethodInstance method){
        this.methods.add(method);
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
}
