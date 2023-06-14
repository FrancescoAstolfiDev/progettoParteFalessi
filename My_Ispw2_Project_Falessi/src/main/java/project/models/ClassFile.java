package project.models;


import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class ClassFile {
    private String content;
    private String path;
    private int lineCodeComment;
    private int lineCodeNoComment;
    private int ciclomaticComplexity;
    private boolean isBuggy;
    private int nr;
    private int nAuth;
    private int age;
    private Date creationDate;
    private int locAdded;
    private int maxLocAdded;

    private int churn;
    private int maxChurn;
    private double meanChurn;

    private int addedLines;
    private int deletedLines;
    private List<String> authors;


    public ClassFile(String content,String path){
        this.path = path;
        this.content = content;
        this.authors = new ArrayList<>();
        this.isBuggy = false;
        this.nr = 0;
        this.addedLines = 0;
        this.deletedLines = 0;
        this.meanChurn = 0;
        this.locAdded = 0;
        this.maxLocAdded = 0;
        this.age = -1;
        this.creationDate = null;
    }
    public void setMeanChurn(double meanChurn){
        this.meanChurn = meanChurn;
    }
    public double getMeanChurn(){
        return this.meanChurn;
    }
    public void setLocAdded(int number){
        this.locAdded = number;
    }
    public int getLocAdded(){
        return this.locAdded;
    }
    public void setChurn(int churn){
        this.churn = churn;
    }
    public int getChurn(){
        return this.churn;
    }

    public void setMaxLocAdded(int maxLocAdded){
        this.maxLocAdded = maxLocAdded;
    }
    public int getMaxLocAdded(){
        return this.maxLocAdded;
    }

    public void setMaxChurn(int maxChurn){
        this.maxChurn = maxChurn;
    }
    public int getMaxChurn(){
        return this.maxChurn;
    }

    public void setAddedLines(int addedLines) {
        this.addedLines += addedLines;
    }

    public int getAddedLines() {
        return this.addedLines;
    }

    public void setDeletedLines(int deletedLines) {
        this.deletedLines += deletedLines;
    }

    public int getDeletedLines() {
        return this.deletedLines;
    }

    public int getAge(){
        return this.age;
    }

    public void setCreationDate(Date date){
        this.creationDate = date;
    }

    public Date getCreationDate(){
        return this.creationDate;
    }

    public void setAge(int age){
        this.age = age;
    }

    public void addAuthor(String name){
        if(!this.authors.contains(name)){
            authors.add(name);
            this.nAuth = authors.size();
        }
    }

    public int getnAuth() {
        return this.nAuth;
    }

    public void incrementNR(){
        this.nr = this.nr + 1;
    }
    public int getNR(){
        return this.nr;
    }
    public int getCiclomaticComplexity(){
        return this.ciclomaticComplexity;
    }
    public void setBuggy(boolean isBuggy){
        this.isBuggy = isBuggy;
    }
    public boolean getBuggy(){
        return this.isBuggy;
    }
    public void setCiclomaticComplexity(int num){
        this.ciclomaticComplexity = num;
    }

    public int getLineCodeComment(){
        return this.lineCodeComment;
    }

    public void setLineCodeComment(int lineCodeComment){
        this.lineCodeComment = lineCodeComment;
    }

    public int getLineCodeNoComment(){
        return this.lineCodeNoComment;
    }

    public void setLineCodeNoComment(int lineCodeNoComment){
        this.lineCodeNoComment = lineCodeNoComment;
    }

    public String getContent() {
        return content;
    }

    public String getPath(){
        return this.path;
    }
}
