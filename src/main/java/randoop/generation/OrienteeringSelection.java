package randoop.generation;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import randoop.sequence.ExecutableSequence;
import randoop.sequence.Sequence;
import randoop.util.Randomness;
import randoop.util.SimpleList;

/**
 * Implements the Orienteering component, as described by the paper "GRT: Program-Analysis-Guided
 * Random Testing" by Ma et. al (appears in ASE 2015):
 * https://people.kth.se/~artho/papers/lei-ase2015.pdf .
 *
 * <p>Biases input selection towards sequences that have lower execution cost. Execution cost is
 * measured by the number of method calls in a sequence and the time it takes to execute.
 *
 * <p>Our implementation of Orienteering differs from that described in the GRT paper in that we do
 * not measure the time of every execution of a sequence. Instead, we assume that a sequence's
 * execution time is equal to the execution time of its first run. We believe this assumption is
 * reasonable since a sequence does not take any inputs (it is self-contained), so its execution
 * time probably does not differ greatly between separate runs.
 *
 * <p>The GRT paper also does not describe how to handle input sequences that have an execution time
 * of zero, such as one that only includes the assignment of a primitive type {@code byte byte0 =
 * (byte)1;}. We assign these input sequences an execution time of 1 nanosecond to prevent division
 * by zero when later computing weights.
 */
public class OrienteeringSelection extends InputSequenceSelector {
  /** Map from a sequence to its details used for computing its weight. */
  private final Map<Sequence, SequenceDetails> sequenceDetailsMap = new HashMap<>();

  /**
   * Map from a sequence to its weight. Although {@link SequenceDetails} contains a {@link
   * Sequence}'s weight, this map is needed by {@link Randomness} to select a random element from a
   * weighted list. The invariant is that {@code weightMap} will have the same exact keys as {@code
   * sequenceDetailsMap} and that the weight within each {@code SequenceDetail} will be the same for
   * the corresponding {@link Sequence}.
   */
  private final Map<Sequence, Double> weightMap = new HashMap<>();

  /**
   * A class used to contain information needed by Orienteering to compute a weight for a sequence.
   */
  private static class SequenceDetails {
    /** A {@link Sequence}'s weight. */
    private double weight;

    /** Number of times this sequence has been selected by {@link OrienteeringSelection}. */
    private int selectionCount;

    /** The square root of the number of method calls of the sequence. */
    private final double methodSizeSqrt;

    /** The execution time of the sequence. */
    private final long executionTime;

    /**
     * Initialize the details for this sequence.
     *
     * @param methodSizeSqrt the square root of the number of method calls for this sequence
     * @param executionTime the execution time of this sequence.
     */
    public SequenceDetails(double methodSizeSqrt, long executionTime) {
      this.methodSizeSqrt = methodSizeSqrt;
      this.executionTime = executionTime;
      this.selectionCount = 0;

      updateWeight();
    }

    /** Increments the selection count. */
    public void incrementSelectionCount() {
      selectionCount += 1;
    }

    /**
     * Retrieve the weight of the sequence.
     *
     * @return the weight of the sequence
     */
    public double getWeight() {
      return weight;
    }

    /**
     * Compute the weight of a sequence. The formula for a sequence's weight is:
     *
     * <p>1.0 / (k * seq.exec_time * sqrt(seq.meth_size))
     *
     * <p>where k is the number of selections of seq and exec_time is the execution time of seq and
     * meth_size is the number of method call statements in seq. This formula is a slight
     * simplification of the one described in the GRT paper which maintains a separate exec_time for
     * each execution of seq. However, we assume that every execution time for a sequence is the
     * same as the first execution.
     */
    private void updateWeight() {
      // To prevent division by zero, we use a selection count of 1 if this sequence has not yet
      // been selected.
      if (selectionCount == 0) {
        selectionCount = 1;
      }

      weight = 1.0 / (selectionCount * executionTime * methodSizeSqrt);
    }
  }

  /**
   * Initialize {@link OrienteeringSelection} and assign a weight to each {@link Sequence} within
   * the given set of seed sequences. This ensures that later, Orienteering will always have a
   * corresponding {@link SequenceDetails} and therefore a corresponding weight for every {@link
   * Sequence} within a list of candidates for selection.
   *
   * @param seedSequences set of seed sequences
   */
  public OrienteeringSelection(Set<Sequence> seedSequences) {
    for (Sequence seedSequence : seedSequences) {
      // We assume that every seed sequence will have an execution time of 1 nanosecond.
      createSequenceDetailsWithExecutionTime(seedSequence, 1L);
    }
  }

  /**
   * Bias input selection towards lower-cost sequences.
   *
   * @param candidates sequences to choose from
   * @return the chosen sequence
   */
  @Override
  public Sequence selectInputSequence(SimpleList<Sequence> candidates) {
    double totalWeight = computeTotalWeightForCandidates(candidates);

    Sequence selectedSequence = Randomness.randomMemberWeighted(candidates, weightMap, totalWeight);

    // Compute and update the weight of the selected sequence which will be affected by its
    // increased selection count.
    SequenceDetails sequenceDetails = sequenceDetailsMap.get(selectedSequence);
    sequenceDetails.incrementSelectionCount();
    sequenceDetails.updateWeight();
    weightMap.put(selectedSequence, sequenceDetails.getWeight());

    return selectedSequence;
  }

  /**
   * Compute the total weight of the list of candidate {@link Sequence}s.
   *
   * @param candidates list of candidate sequences
   * @return the total weight of the input candidate list
   */
  private double computeTotalWeightForCandidates(SimpleList<Sequence> candidates) {
    double totalWeight = 0;
    for (int i = 0; i < candidates.size(); i++) {
      totalWeight += sequenceDetailsMap.get(candidates.get(i)).getWeight();
    }
    return totalWeight;
  }

  /**
   * Create a {@link SequenceDetails} for the underlying {@link Sequence} in the given {@link
   * ExecutableSequence}.
   *
   * @param eSeq the recently executed sequence which is new and unique, and has just been executed.
   *     It contains its overall execution time for the underlying {@link Sequence}.
   */
  @Override
  public void createdExecutableSequence(ExecutableSequence eSeq) {
    createSequenceDetailsWithExecutionTime(eSeq.sequence, eSeq.exectime);
  }

  /**
   * Create a {@link SequenceDetails} for the given {@link Sequence} with the corresponding
   * execution time.
   *
   * @param sequence the sequence to add
   * @param executionTime the execution time of the sequence
   */
  private void createSequenceDetailsWithExecutionTime(Sequence sequence, long executionTime) {
    double methodSqrtSize = methodSizeSquareRootForSequence(sequence);

    SequenceDetails sequenceDetails = new SequenceDetails(methodSqrtSize, executionTime);

    sequenceDetailsMap.put(sequence, sequenceDetails);
    weightMap.put(sequence, sequenceDetails.getWeight());
  }

  /**
   * Compute the method size square root of the given sequence. This is the square root of the
   * number of method call statements within the given sequence.
   *
   * <p>To prevent division by zero, we use 1 for a sequence with no method calls.
   *
   * @param sequence the sequence whose the method size square root to get
   * @return square root of the number of method calls in the given sequence
   */
  private double methodSizeSquareRootForSequence(Sequence sequence) {
    double methodSizeSqrt = Math.sqrt(sequence.numMethodCalls());
    if (methodSizeSqrt == 0) {
      methodSizeSqrt = 1.0;
    }

    return methodSizeSqrt;
  }
}
