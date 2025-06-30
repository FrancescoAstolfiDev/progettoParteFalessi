package project.models;

import org.eclipse.jgit.revwalk.RevCommit;

import java.util.*;

public class Release {

	private int id;
	private String name;
	private Date date;
	private double currentProportion;


	private List<RevCommit> allReleaseCommits;
	private RevCommit lastCommitPreRelease;

	private Map<String, ClassFile> classFileMap;

	public Release(int id, String name, Date date) {
		this.id = id;
		this.name = name;
		this.date = date;
		this.allReleaseCommits = new ArrayList<>();
		this.classFileMap = new HashMap<>();
		this.lastCommitPreRelease = null;
	}
	public static int getId(String releaseName, List<Release> releaseList){
		int releaseId=-1;
		for(Release r:releaseList){
			if(r.getName().equals(releaseName)){
				releaseId=r.getId();
				break;
			}
		}
		return releaseId;
	}

	public int getId() {
		return id;
	}

	public void setId(int id) {
		this.id = id;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public Date getDate() {
		return date;
	}

	public void setDate(Date date) {
		this.date = date;
	}

	public double getCurrentProportion() {
		return this.currentProportion;
	}

	public void setCurrentProportion(double proportion) {

		this.currentProportion = proportion;
	}

	public void setLastCommitPreRelease(RevCommit commit) {
		this.lastCommitPreRelease = commit;
	}

	public RevCommit getLastCommitPreRelease() {
		return this.lastCommitPreRelease;
	}

	public void addCommitToReleaseList(RevCommit commit) {
		this.allReleaseCommits.add(commit);
	}

	public List<RevCommit> getAllReleaseCommits() {
		return this.allReleaseCommits;
	}

	public void addClassFile(ClassFile classFile) {
		this.classFileMap.put(ClassFile.getKey(classFile), classFile);
	}

	public List<ClassFile> getClassFileByPath(String path) {
		List<ClassFile> classFiles = new ArrayList<>();
		for (Map.Entry<String, ClassFile> entry : this.classFileMap.entrySet()) {
			if (entry.getKey().startsWith(path)) {
				classFiles.add(entry.getValue());
			}
		}
		return classFiles;
	}

	public ClassFile getClassFileByKey(String keyClass) {
		return classFileMap.get(keyClass);
	}







	public  ClassFile findClassFileByApproxName(String classNameToNormalize) {
		String normalizedClassName = ClassFile.getNameClass(classNameToNormalize);
		String normalizedPath = ClassFile.extractPath(classNameToNormalize);
		for (Map.Entry<String, ClassFile> entry : classFileMap.entrySet()) {
			ClassFile classFile = entry.getValue();
			String className=classFile.getClassName();
			if (className.equals(normalizedClassName) && classFile.getPath().contains(normalizedPath) ) {
				return entry.getValue();
			}
		}
		return null; // Nessuna corrispondenza trovata
	}

	public List<ClassFile> getReleaseAllClass() {
		return new ArrayList<>(classFileMap.values());
	}

	public ClassFile[] getClassFiles() {
		return classFileMap.values().toArray(new ClassFile[0]);
	}

	public Date getReleaseDate() {
		return this.date;
	}

}