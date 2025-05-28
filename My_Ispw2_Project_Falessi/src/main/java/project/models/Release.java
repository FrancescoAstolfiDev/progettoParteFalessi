package project.models;

import org.eclipse.jgit.revwalk.RevCommit;

import java.util.*;

public class Release {

	private int id;
	private String name;
	private Date date;
	private double currentProportion;

	private List<Ticket> allReleaseTicket;
	private List<RevCommit> allReleaseCommits;
	private RevCommit lastCommitPreRelease;

	private List<MethodInstance> releaseAllMethods;
	private Map<String, ClassFile> classFileMap;
	private List<ClassFile> releaseAllClass;

	public Release(int id, String name, Date date) {
		this.id = id;
		this.name = name;
		this.date = date;
		this.allReleaseCommits = new ArrayList<>();
		this.releaseAllMethods = new ArrayList<>();
		this.classFileMap = new HashMap<>();
		this.lastCommitPreRelease = null;
		this.allReleaseTicket = new ArrayList<>();
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

	public void setAllReleaseTicket(List<Ticket> tickets) {
		this.allReleaseTicket = tickets;
	}

	public List<Ticket> getAllReleaseTicket() {
		return this.allReleaseTicket;
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

	public RevCommit getLastCommit() {
		if (!this.allReleaseCommits.isEmpty()) {
			return this.allReleaseCommits.get(this.allReleaseCommits.size() - 1);
		}
		return null;
	}

	public void addMethod(MethodInstance method) {
		this.releaseAllMethods.add(method);
	}

	public List<MethodInstance> getReleaseAllMethods() {
		return this.releaseAllMethods;
	}

	public void setReleaseAllMethods(List<MethodInstance> allMethods) {
		this.releaseAllMethods = allMethods;
	}

	public MethodInstance getMethodByPathAndName(String classPath, String methodName) {
		for (MethodInstance method : releaseAllMethods) {
			if (method.getFilePath().equals(classPath) && method.getMethodName().equals(methodName)) {
				return method;
			}
		}
		return null;
	}


	public MethodInstance getMethodByIdentifier(String identifier) {
		for (MethodInstance method : releaseAllMethods) {
			if (method.getFullSignature().equals(identifier)) {
				return method;
			}
		}
		return null;
	}

	public MethodInstance getMethodBySignature(String fullSignature) {
		for (MethodInstance method : releaseAllMethods) {
			if (method.getFullSignature().equals(fullSignature)) {
				return method;
			}
		}
		return null;
	}

	public MethodInstance getMethodByPath(String file) {
		for (MethodInstance method : releaseAllMethods) {
			if (method.getFilePath().equals(file)) {
				return method;
			}
		}
		return null;
	}

	public List<MethodInstance> getMethodInstancesByFilePath(String path) {
		List<MethodInstance> result = new ArrayList<>();
		for (MethodInstance method : releaseAllMethods) {
			if (method.getFilePath().equals(path)) {
				result.add(method);
			}
		}
		return result;
	}

	public void addClassFile(ClassFile classFile) {

		this.classFileMap.put(classFile.getPath(), classFile);
	}

	public ClassFile getClassFileByPath(String path) {

		return this.classFileMap.get(path);
	}
	public static  String normalizeToModuleAndClass(String fullClassName) {
		// Rimuove eventuali classi interne o anonime (es. $Anonymous4)
		int dollarIndex = fullClassName.indexOf('$');
		String cleanName = (dollarIndex != -1) ? fullClassName.substring(0, dollarIndex) : fullClassName;

		// Trova l'indice del modulo (es. "benchmark")
		String[] parts = cleanName.split("\\.");
		for (int i = 0; i < parts.length; i++) {
			if (parts[i].equals("benchmark")) {
				// Restituisce da "benchmark" in poi, unito con slash
				return String.join("/", Arrays.copyOfRange(parts, i, parts.length));
			}
		}

		// Se "benchmark" non viene trovato, restituisci solo il nome della classe
		int lastDot = cleanName.lastIndexOf('.');
		String classNameOnly = (lastDot != -1) ? cleanName.substring(lastDot + 1) : cleanName;
		return classNameOnly;
	}

	public  ClassFile findClassFileByApproxName(String className) {
		String normalizedTarget = normalizeToModuleAndClass(className);

		for (Map.Entry<String, ClassFile> entry : classFileMap.entrySet()) {
			String normalizedKey = entry.getKey();
			if (normalizedKey.contains(normalizedTarget)) {
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

	public void setReleaseAllClass(List<ClassFile> allClass){
		this.releaseAllClass = allClass;
	}
}
