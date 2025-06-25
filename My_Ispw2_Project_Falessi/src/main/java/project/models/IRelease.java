package project.models;

import org.eclipse.jgit.revwalk.RevCommit;

import java.util.Date;
import java.util.List;

/**
 * Interface for Release objects.
 * This interface is designed to break dependency cycles in the codebase.
 */
public interface IRelease {
    
    /**
     * Get the ID of the release.
     * @return the release ID
     */
    int getId();
    
    /**
     * Get the name of the release.
     * @return the release name
     */
    String getName();
    
    /**
     * Get the date of the release.
     * @return the release date
     */
    Date getDate();
    
    /**
     * Get the release date (alias for getDate for backward compatibility).
     * @return the release date
     */
    Date getReleaseDate();
    
    /**
     * Get the current proportion value.
     * @return the current proportion
     */
    double getCurrentProportion();
    
    /**
     * Get the last commit before the release.
     * @return the last commit
     */
    RevCommit getLastCommitPreRelease();
    
    /**
     * Get all commits associated with this release.
     * @return list of commits
     */
    List<RevCommit> getAllReleaseCommits();
    
    /**
     * Get the last commit in the release.
     * @return the last commit
     */
    RevCommit getLastCommit();
    
    /**
     * Get all tickets associated with this release.
     * @return list of tickets
     */
    List<Ticket> getAllReleaseTicket();
    
    /**
     * Get all methods in this release.
     * @return list of methods
     */
    List<MethodInstance> getReleaseAllMethods();
    
    /**
     * Get a method by its path and name.
     * @param classPath the class path
     * @param methodName the method name
     * @return the method instance or null if not found
     */
    MethodInstance getMethodByPathAndName(String classPath, String methodName);
    
    /**
     * Get a method by its identifier.
     * @param identifier the method identifier
     * @return the method instance or null if not found
     */
    MethodInstance getMethodByIdentifier(String identifier);
    
    /**
     * Get a method by its signature.
     * @param fullSignature the method signature
     * @return the method instance or null if not found
     */
    MethodInstance getMethodBySignature(String fullSignature);
    
    /**
     * Get a method by its file path.
     * @param file the file path
     * @return the method instance or null if not found
     */
    MethodInstance getMethodByPath(String file);
    
    /**
     * Get all methods in a specific file.
     * @param path the file path
     * @return list of methods
     */
    List<MethodInstance> getMethodInstancesByFilePath(String path);
    
    /**
     * Get a class file by its path.
     * @param path the class file path
     * @return the class file or null if not found
     */
    ClassFile getClassFileByPath(String path);
    
    /**
     * Find a class file by an approximate name.
     * @param className the approximate class name
     * @return the class file or null if not found
     */
    ClassFile findClassFileByApproxName(String className);
    
    /**
     * Get all class files in this release.
     * @return list of class files
     */
    List<ClassFile> getReleaseAllClass();
    
    /**
     * Get all class files as an array.
     * @return array of class files
     */
    ClassFile[] getClassFiles();
}