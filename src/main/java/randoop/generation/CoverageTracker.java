package randoop.generation;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.jacoco.core.analysis.*;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.SessionInfoStore;
import org.jacoco.core.instr.Instrumenter;
import org.jacoco.core.runtime.IRuntime;
import org.jacoco.core.runtime.LoggerRuntime;
import org.jacoco.core.runtime.RuntimeData;

/**
 * Tracks the coverage of each method under test. Largely based on
 * http://www.jacoco.org/jacoco/trunk/doc/examples/java/CoreTutorial.java
 */
public class CoverageTracker {
  public static CoverageTracker instance = new CoverageTracker();

  private final IRuntime runtime = new LoggerRuntime();
  private final InstrumentingClassLoader instrumentingClassLoader;

  private final ExecutionDataStore executionData = new ExecutionDataStore();
  private final SessionInfoStore sessionInfos = new SessionInfoStore();

  private final Instrumenter instrumenter;
  private final RuntimeData data;

  /** Map from method name to coverage details. */
  private final Map<String, BranchCoverage> coverageDetailsMap = new HashMap<>();

  /** Names of all the classes under test */
  private Set<String> classesUnderTest = new HashSet<>();

  /**
   * Coverage details related to a single method under test. Tracks total number of branches and
   * number of uncovered branches.
   */
  public static class BranchCoverage {
    /** Total number of branches. */
    public int totalBranches;
    /** Number of uncovered branches. */
    public int uncoveredBranches;
  }

  private CoverageTracker() {
    this.instrumentingClassLoader = new InstrumentingClassLoader(this);
    this.instrumenter = new Instrumenter(runtime);
    this.data = new RuntimeData();

    try {
      this.runtime.startup(data);
    } catch (Exception e) {
      e.printStackTrace();
      System.exit(1);
    }
  }

  /**
   * Set which classes are under test.
   *
   * @param classesUnderTest names of all the classes under test
   */
  public void setClassesUnderTest(Set<String> classesUnderTest) {
    this.classesUnderTest = new HashSet<>(classesUnderTest);
  }

  /**
   * Attempts to instrument a class and return the byte array representation.
   *
   * @param className name of the class to instrument
   * @return the instrumented bytes of the class, null if instrumentation failed
   */
  public byte[] instrumentClass(String className) {
    // Only instrument classes under test and their nested classes.
    if (!classesUnderTest.contains(className) && !isNestedClass(className)) {
      return null;
    }

    final byte[] instrumented;
    final String resource = getResourceFromClassName(className);
    InputStream original = getClass().getResourceAsStream(resource);

    if (original == null) {
      throw new Error("No resource with name: " + resource + " found by CoverageTracker!");
    }

    try {
      // Instrument the class so that branch coverage data can be obtained.
      instrumented = instrumenter.instrument(original, className);
      original.close();
    } catch (IOException e) {
      e.printStackTrace();
      System.exit(1);
      return null;
    }

    return instrumented;
  }

  /**
   * Determine if the given class is a nested class of a class under test.
   *
   * @param className the name of the class to check
   * @return true iff the given class is a nested class of a class under test
   */
  private boolean isNestedClass(String className) {
    for (String classUnderTest : classesUnderTest) {
      if (className.startsWith(classUnderTest + "$")) {
        return true;
      }
    }
    return false;
  }

  /**
   * Instruments and then loads the class with the given class name.
   *
   * @param className name of the class
   * @return {@code Class} object that has been instrumented for coverage data collection. Returns
   *     null if class with target name cannot be found.
   */
  public Class<?> instrumentAndLoadClass(String className) {
    Class<?> instrumentedClass = null;
    try {
      instrumentedClass = instrumentingClassLoader.loadClass(className);
    } catch (ClassNotFoundException e) {
      e.printStackTrace();
      System.exit(1);
    }
    return instrumentedClass;
  }

  /**
   * Collect coverage information for all methods under test. At this point, coverage data has
   * already been generated as Randoop has been constructing and executing its test sequences.
   * Coverage data is now collected and summarized. The {@code coverageDetailsMap} is updated to
   * contain the updated coverage information of each method branch.
   */
  public void collect() {
    // Collect coverage information.
    data.collect(executionData, sessionInfos, false);

    CoverageBuilder coverageBuilder = new CoverageBuilder();
    Analyzer analyzer = new Analyzer(executionData, coverageBuilder);

    // Analyze the coverage of each of the tracked classes.
    for (String className : classesUnderTest) {
      String resource = getResourceFromClassName(className);
      InputStream original = getClass().getResourceAsStream(resource);
      try {
        analyzer.analyzeClass(original, className);
        original.close();
      } catch (IOException e) {
        throw new Error(e);
      }
    }

    // Collect the branch coverage information.
    for (final IClassCoverage cc : coverageBuilder.getClasses()) {
      for (final IMethodCoverage cm : cc.getMethods()) {
        String methodName = cc.getName() + "." + cm.getName();

        // System.out.println(methodName + " - " + cm.getBranchCounter().getMissedRatio());

        BranchCoverage methodDetails = coverageDetailsMap.get(methodName);
        if (methodDetails == null) {
          methodDetails = new BranchCoverage();
        }
        methodDetails.totalBranches = cm.getBranchCounter().getTotalCount();
        methodDetails.uncoveredBranches = cm.getBranchCounter().getMissedCount();

        coverageDetailsMap.put(methodName, methodDetails);
      }
    }
    // System.out.println("---------------------------");
  }

  /**
   * Construct the absolute resource name of a class given a class name.
   *
   * @param className name of class
   * @return absolute resource name of the class
   */
  private String getResourceFromClassName(String className) {
    return '/' + className.replace('.', '/') + ".class";
  }

  /** Clean up the coverage tracker instance. */
  public void finish() {
    runtime.shutdown();
  }

  /**
   * Returns the coverage details associated with the input method.
   *
   * @param methodName name of the method to examine
   * @return coverage details associated with the method
   */
  public BranchCoverage getDetailsForMethod(String methodName) {
    return this.coverageDetailsMap.get(methodName);
  }
}
