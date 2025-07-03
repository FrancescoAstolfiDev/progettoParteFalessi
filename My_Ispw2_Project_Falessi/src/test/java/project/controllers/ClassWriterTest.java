package project.controllers;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import project.models.DataSetType;
import project.models.MethodInstance;
import project.models.Release;
import project.statefull.ConstantsWindowsFormat;
import project.statefull.OpenjpaEntry;
import project.utils.EntryProject;
import project.utils.WhatIf;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ClassWriterTest {

    private Map<String, MethodInstance> testMethodInstances;
    private Release testRelease;
    private Path originalCsvPath;
    private Path originalTestCsvPath;
    private Path originalPartialsCsvPath;

    @TempDir
    Path tempDir;

    @BeforeEach
    public void setUp() throws IOException {
        // Save original paths
        originalCsvPath = ConstantsWindowsFormat.CSV_PATH;
        originalTestCsvPath = ConstantsWindowsFormat.TEST_CSV_PATH;
        originalPartialsCsvPath = ConstantsWindowsFormat.PARTIALS_CSV_PATH;

        // Create temporary directories for test output
        Path tempCsvPath = tempDir.resolve("csv");
        Path tempTestCsvPath = tempCsvPath.resolve("tests");
        Path tempPartialsCsvPath = tempCsvPath.resolve("partials");

        Files.createDirectories(tempCsvPath);
        Files.createDirectories(tempTestCsvPath);
        Files.createDirectories(tempPartialsCsvPath);

        // Set up test data
        testRelease = new Release(1, "1.0.0", new Date());
        testMethodInstances = createTestMethodInstances();

        // Use reflection to modify the static final fields for testing
        modifyStaticFinalField(ConstantsWindowsFormat.class, "CSV_PATH", tempCsvPath);
        modifyStaticFinalField(ConstantsWindowsFormat.class, "TEST_CSV_PATH", tempTestCsvPath);
        modifyStaticFinalField(ConstantsWindowsFormat.class, "PARTIALS_CSV_PATH", tempPartialsCsvPath);
    }

    @AfterEach
    public void tearDown() throws IOException {
        // Restore original paths
        modifyStaticFinalField(ConstantsWindowsFormat.class, "CSV_PATH", originalCsvPath);
        modifyStaticFinalField(ConstantsWindowsFormat.class, "TEST_CSV_PATH", originalTestCsvPath);
        modifyStaticFinalField(ConstantsWindowsFormat.class, "PARTIALS_CSV_PATH", originalPartialsCsvPath);
    }

    private void modifyStaticFinalField(Class<?> clazz, String fieldName, Object newValue) {
        try {
            java.lang.reflect.Field field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true);

            java.lang.reflect.Field modifiersField = java.lang.reflect.Field.class.getDeclaredField("modifiers");
            modifiersField.setAccessible(true);
            modifiersField.setInt(field, field.getModifiers() & ~java.lang.reflect.Modifier.FINAL);

            field.set(null, newValue);
        } catch (Exception e) {
            System.err.println("Error modifying static final field: " + e.getMessage());
        }
    }

    private Map<String, MethodInstance> createTestMethodInstances() {
        Map<String, MethodInstance> instances = new HashMap<>();
        EntryProject openjpaEntry = new OpenjpaEntry();

        // Create a method instance with no smells
        MethodInstance method1 = new MethodInstance("path/to/Class1.java", "method1", "()V");
        method1.setReleaseName("1.0.0");
        method1.setClassName("Class1");
        method1.setLoc(10);
        method1.setWmc(5);
        method1.setQtyAssigment(3);
        method1.setQtyMathOperations(2);
        method1.setQtyTryCatch(1);
        method1.setQtyReturn(1);
        method1.setFanin(2);
        method1.setFanout(3);
        method1.setAge(5);
        method1.setnAuth(1);
        method1.setNr(2);
        method1.setnSmells(0);
        method1.setBuggy(false);
        instances.put("method1", method1);

        // Create a method instance with smells
        MethodInstance method2 = new MethodInstance("path/to/Class2.java", "method2", "()V");
        method2.setReleaseName("1.0.0");
        method2.setClassName("Class2");
        method2.setLoc(20);
        method2.setWmc(10);
        method2.setQtyAssigment(6);
        method2.setQtyMathOperations(4);
        method2.setQtyTryCatch(2);
        method2.setQtyReturn(2);
        method2.setFanin(4);
        method2.setFanout(6);
        method2.setAge(10);
        method2.setnAuth(2);
        method2.setNr(4);
        method2.setnSmells(2);
        method2.setBuggy(true);
        instances.put("method2", method2);

        return instances;
    }

    @Test
    public void testWriteResultsToFileWithNormalDataSet() throws IOException {
        // Test writing results with normal dataset
        ClassWriter.writeResultsToFile("TEST_OUTPUT", testMethodInstances, DataSetType.TRAINING);

        // Verify the file was created
        Path outputFile = ConstantsWindowsFormat.CSV_PATH.resolve("TEST_OUTPUT.csv");
        assertTrue(Files.exists(outputFile), "Output file should exist");

        // Verify file content
        verifyFileContent(outputFile, 2); // Should contain both method instances
    }

    @Test
    public void testWriteResultsToFileWithTestDataSet() throws IOException {
        // Test writing results with test dataset
        ClassWriter.writeResultsToFile("TEST_OUTPUT", testMethodInstances, DataSetType.TEST);

        // Verify the file was created
        Path outputFile = ConstantsWindowsFormat.TEST_CSV_PATH.resolve("TEST_OUTPUT.csv");
        assertTrue(Files.exists(outputFile), "Output file should exist");

        // Verify file content
        verifyFileContent(outputFile, 2); // Should contain both method instances
    }

    @Test
    public void testWriteResultsToFileWithPartialDataSet() throws IOException {
        // Test writing results with partial dataset
        ClassWriter.writeResultsToFile("TEST_OUTPUT", testMethodInstances, DataSetType.PARTIAL);

        // Verify the file was created
        Path outputFile = ConstantsWindowsFormat.PARTIALS_CSV_PATH.resolve("TEST_OUTPUT.csv");
        assertTrue(Files.exists(outputFile), "Output file should exist");

        // Verify file content
        verifyFileContent(outputFile, 2); // Should contain both method instances
    }

    @Test
    public void testWriteResultsToFileWithBMatrix() throws IOException {
        // Test writing results with B matrix
        ClassWriter.writeResultsToFile("TEST_OUTPUT" + WhatIf.B_MATRIX.getName(), testMethodInstances, DataSetType.TRAINING);

        // Verify the file was created
        Path outputFile = ConstantsWindowsFormat.CSV_PATH.resolve("TEST_OUTPUT" + WhatIf.B_MATRIX.getName() + ".csv");
        assertTrue(Files.exists(outputFile), "Output file should exist");

        // Verify file content - should only contain method2 (with smells) but with nSmells set to 0
        verifyFileContent(outputFile, 1);
        verifyNSmellsSetToZero(outputFile);
    }
    @Test
    public void testWriteResultsToFileWithBPlusMatrix() throws IOException {
        // Test writing results with B_PLUS matrix
        ClassWriter.writeResultsToFile("TEST_OUTPUT" + WhatIf.B_PLUS_MATRIX.getName(), testMethodInstances, DataSetType.TRAINING);

        // Verify the file was created
        Path outputFile = ConstantsWindowsFormat.CSV_PATH.resolve("TEST_OUTPUT" + WhatIf.B_PLUS_MATRIX.getName() + ".csv");
        assertTrue(Files.exists(outputFile), "Output file should exist");

        // Verify file content - should only contain method2 (with smells) and nSmells should be greater than 0
        verifyFileContent(outputFile, 1);
        verifyNSmellsGreaterThanZero(outputFile);
    }

    @Test
    public void testWriteResultsToFileWithCMatrix() throws IOException {
        // Test writing results with C matrix
        ClassWriter.writeResultsToFile("TEST_OUTPUT" + WhatIf.C_MATRIX.getName(), testMethodInstances, DataSetType.TRAINING);

        // Verify the file was created
        Path outputFile = ConstantsWindowsFormat.CSV_PATH.resolve("TEST_OUTPUT" + WhatIf.C_MATRIX.getName() + ".csv");
        assertTrue(Files.exists(outputFile), "Output file should exist");

        // Verify file content - should only contain method1 (without smells)
        verifyFileContent(outputFile, 1);
        verifyOnlyMethodsWithoutSmells(outputFile);
    }

    @Test
    public void testWriteResultsToFileWithReleaseAndProjectName() {
        // Test the public method that takes a Release and project name
        ClassWriter.writeResultsToFile(testRelease, "TEST", testMethodInstances, DataSetType.TRAINING);

        // Verify the file was created
        Path outputFile = ConstantsWindowsFormat.CSV_PATH.resolve("TESTTrain1.csv");
        assertTrue(Files.exists(outputFile), "Output file should exist");
    }

    @Test
    public void testWriteResultsToFileWithNullRelease() {
        // Test with null release
        ClassWriter.writeResultsToFile(null, "TEST", testMethodInstances, DataSetType.TRAINING);

        // No file should be created
        Path outputFile = ConstantsWindowsFormat.CSV_PATH.resolve("TESTTrain.csv");
        assertFalse(Files.exists(outputFile), "No output file should be created with null release");
    }

    private void verifyFileContent(Path filePath, int expectedLines) throws IOException {
        int lineCount = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath.toFile()))) {
            // Skip header line
            reader.readLine();

            while (reader.readLine() != null) {
                lineCount++;
            }
        }

        assertEquals(expectedLines, lineCount, "File should contain " + expectedLines + " data lines");
    }

    private void verifyNSmellsSetToZero(Path filePath) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath.toFile()))) {
            // Skip header line
            reader.readLine();

            String line = reader.readLine();
            if (line != null) {
                String[] parts = line.split(",");
                // nSmells is the 16th field (0-based index 15)
                assertEquals("0", parts[15], "nSmells should be set to 0 for B matrix");
            }
        }
    }

    private void verifyNSmellsGreaterThanZero(Path filePath) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath.toFile()))) {
            // Skip header line
            reader.readLine();

            String line = reader.readLine();
            if (line != null) {
                String[] parts = line.split(",");
                // nSmells is the 16th field (0-based index 15)
                int nSmells = Integer.parseInt(parts[15]);
                assertTrue(nSmells > 0, "nSmells should be greater than 0 for B_PLUS matrix");
            }
        }
    }

    private void verifyOnlyMethodsWithoutSmells(Path filePath) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath.toFile()))) {
            // Skip header line
            reader.readLine();

            String line = reader.readLine();
            if (line != null) {
                String[] parts = line.split(",");
                // nSmells is the 16th field (0-based index 15)
                assertEquals("0", parts[15], "Only methods without smells should be included for C matrix");
            }
        }
    }

    @Test
    public void testShouldSkipResult() throws Exception {
        // Use reflection to access the private shouldSkipResult method
        java.lang.reflect.Method shouldSkipResultMethod = ClassWriter.class.getDeclaredMethod(
                "shouldSkipResult", MethodInstance.class, String.class);
        shouldSkipResultMethod.setAccessible(true);

        // Test case 1: Method with negative age should be skipped
        MethodInstance methodNegativeAge = new MethodInstance("path/to/Class.java", "methodNegativeAge", "()V");
        methodNegativeAge.setAge(-1);
        methodNegativeAge.setReleaseName("1.0.0");
        methodNegativeAge.setnSmells(0);
        boolean result1 = (boolean) shouldSkipResultMethod.invoke(null, methodNegativeAge, "test.csv");
        assertTrue(result1, "Method with negative age should be skipped");

        // Test case 2: Method with null release name should be skipped
        MethodInstance methodNullRelease = new MethodInstance("path/to/Class.java", "methodNullRelease", "()V");
        methodNullRelease.setAge(1);
        methodNullRelease.setReleaseName(null);
        methodNullRelease.setnSmells(0);
        boolean result2 = (boolean) shouldSkipResultMethod.invoke(null, methodNullRelease, "test.csv");
        assertTrue(result2, "Method with null release name should be skipped");

        // Test case 3: Method with no smells should be skipped for B matrix
        MethodInstance methodNoSmells = new MethodInstance("path/to/Class.java", "methodNoSmells", "()V");
        methodNoSmells.setAge(1);
        methodNoSmells.setReleaseName("1.0.0");
        methodNoSmells.setnSmells(0);
        boolean result3 = (boolean) shouldSkipResultMethod.invoke(null, methodNoSmells, "test" + WhatIf.B_MATRIX.getName() + ".csv");
        assertTrue(result3, "Method with no smells should be skipped for B matrix");

        // Test case 4: Method with smells should not be skipped for B matrix
        MethodInstance methodWithSmells = new MethodInstance("path/to/Class.java", "methodWithSmells", "()V");
        methodWithSmells.setAge(1);
        methodWithSmells.setReleaseName("1.0.0");
        methodWithSmells.setnSmells(2);
        boolean result4 = (boolean) shouldSkipResultMethod.invoke(null, methodWithSmells, "test" + WhatIf.B_MATRIX.getName() + ".csv");
        assertFalse(result4, "Method with smells should not be skipped for B matrix");

        // Test case 5: Method with smells should be skipped for C matrix
        boolean result5 = (boolean) shouldSkipResultMethod.invoke(null, methodWithSmells, "test" + WhatIf.C_MATRIX.getName() + ".csv");
        assertTrue(result5, "Method with smells should be skipped for C matrix");

        // Test case 6: Method with no smells should not be skipped for C matrix
        boolean result6 = (boolean) shouldSkipResultMethod.invoke(null, methodNoSmells, "test" + WhatIf.C_MATRIX.getName() + ".csv");
        assertFalse(result6, "Method with no smells should not be skipped for C matrix");

        // Test case 7: Method with valid properties should not be skipped for normal path
        boolean result7 = (boolean) shouldSkipResultMethod.invoke(null, methodNoSmells, "test.csv");
        assertFalse(result7, "Method with valid properties should not be skipped for normal path");
    }

    @Test
    public void testEscapeCsv() throws Exception {
        // Use reflection to access the private escapeCsv method
        java.lang.reflect.Method escapeCsvMethod = ClassWriter.class.getDeclaredMethod(
                "escapeCsv", String.class);
        escapeCsvMethod.setAccessible(true);

        // Test case 1: Null input should return empty string
        String result1 = (String) escapeCsvMethod.invoke(null, (Object) null);
        assertEquals("", result1, "Null input should return empty string");

        // Test case 2: Normal string without special characters should remain unchanged
        String normalString = "normal string";
        String result2 = (String) escapeCsvMethod.invoke(null, normalString);
        assertEquals(normalString, result2, "Normal string should remain unchanged");

        // Test case 3: String with comma should be enclosed in quotes
        String stringWithComma = "string,with,commas";
        String result3 = (String) escapeCsvMethod.invoke(null, stringWithComma);
        assertEquals("\"string,with,commas\"", result3, "String with comma should be enclosed in quotes");

        // Test case 4: String with quotes should have quotes doubled and be enclosed in quotes
        String stringWithQuotes = "string\"with\"quotes";
        String result4 = (String) escapeCsvMethod.invoke(null, stringWithQuotes);
        assertEquals("\"string\"\"with\"\"quotes\"", result4, "String with quotes should have quotes doubled and be enclosed in quotes");

        // Test case 5: String with newline should be enclosed in quotes
        String stringWithNewline = "string\nwith\nnewlines";
        String result5 = (String) escapeCsvMethod.invoke(null, stringWithNewline);
        assertEquals("\"string\nwith\nnewlines\"", result5, "String with newline should be enclosed in quotes");

        // Test case 6: String with carriage return should be enclosed in quotes
        String stringWithCR = "string\rwith\rcarriage\rreturns";
        String result6 = (String) escapeCsvMethod.invoke(null, stringWithCR);
        assertEquals("\"string\rwith\rcarriage\rreturns\"", result6, "String with carriage return should be enclosed in quotes");

        // Test case 7: String with multiple special characters should be properly escaped
        String complexString = "complex,string\"with\nnewlines\rand\"quotes";
        String result7 = (String) escapeCsvMethod.invoke(null, complexString);
        assertEquals("\"complex,string\"\"with\nnewlines\rand\"\"quotes\"", result7, "Complex string should be properly escaped");
    }
}
