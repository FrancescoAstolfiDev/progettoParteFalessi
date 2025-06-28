package project;

import project.controllers.MethodDataSetExecutor;
import project.utils.Projects;


public class DatasetCreationMain {

	public static void main(String[] args) throws Exception {

 	//scegliere il progetto tra "openjpa" e "bookkeeper"
		String projectName = Projects.BOOKKEEPER.toString();

		MethodDataSetExecutor mainFlow = new MethodDataSetExecutor(projectName);
		mainFlow.executeFlow();

	}
}
