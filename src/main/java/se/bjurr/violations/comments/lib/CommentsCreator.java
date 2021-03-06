package se.bjurr.violations.comments.lib;

import static java.util.logging.Level.INFO;
import static java.util.stream.Collectors.joining;
import static se.bjurr.violations.comments.lib.ChangedFileUtils.findChangedFile;
import static se.bjurr.violations.comments.lib.CommentFilterer.filterCommentsWithContent;
import static se.bjurr.violations.comments.lib.CommentFilterer.filterCommentsWithoutContent;
import static se.bjurr.violations.comments.lib.CommentFilterer.getViolationComments;
import static se.bjurr.violations.comments.lib.ViolationRenderer.createSingleFileCommentContent;
import static se.bjurr.violations.comments.lib.ViolationRenderer.getAccumulatedComments;
import static se.bjurr.violations.lib.util.Utils.checkNotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import se.bjurr.violations.comments.lib.model.ChangedFile;
import se.bjurr.violations.comments.lib.model.Comment;
import se.bjurr.violations.lib.ViolationsLogger;
import se.bjurr.violations.lib.model.Violation;

public class CommentsCreator {
  public static final String FINGERPRINT =
      "<this is a auto generated comment from violation-comments-lib F7F8ASD8123FSDF>";
  public static final String FINGERPRINT_ACC = "<ACCUMULATED-VIOLATIONS>";
  private final ViolationsLogger violationsLogger;
  private final CommentsProvider commentsProvider;
  private final List<ChangedFile> files;
  private final Set<Violation> violations;

  public static void createComments(
      final ViolationsLogger violationsLogger,
      final Set<Violation> violations,
      final CommentsProvider commentsProvider) {

    final CommentsCreator commentsCreator =
        new CommentsCreator(violationsLogger, commentsProvider, violations);
    commentsCreator.createComments();
  }

  CommentsCreator(
      final ViolationsLogger violationsLogger,
      final CommentsProvider commentsProvider,
      final Set<Violation> violations) {
    checkNotNull(violations, "violations");
    checkNotNull(commentsProvider, "commentsProvider");
    this.violationsLogger = checkNotNull(violationsLogger, "violationsLogger");
    this.commentsProvider = commentsProvider;
    this.files = commentsProvider.getFiles();
    Set<Violation> allViolations = violations;
    if (commentsProvider.shouldCommentOnlyChangedFiles()) {
      allViolations = this.filterChanged(this.files, violations);
      this.violationsLogger.log(
          Level.INFO,
          "Found "
              + this.files.size()
              + " changed files with "
              + allViolations.size()
              + " violations");
    }
    if (commentsProvider.getMaxNumberOfViolations() != null
        && allViolations.size() > commentsProvider.getMaxNumberOfViolations()) {
      final List<Violation> list = new ArrayList<>(allViolations);
      final List<Violation> subList = list.subList(0, commentsProvider.getMaxNumberOfViolations());
      this.violations = new TreeSet<>(subList);
      this.violationsLogger.log(
          Level.INFO,
          "Reducing violations " + allViolations.size() + " to " + this.violations.size());
    } else {
      this.violations = allViolations;
    }
  }

  public void createComments() {
    if (this.commentsProvider.shouldCreateCommentWithAllSingleFileComments()) {
      this.createCommentWithAllSingleFileComments();
    }
    if (this.commentsProvider.shouldCreateSingleFileComment()) {
      if (!this.commentsProvider.shouldCommentOnlyChangedFiles()) {
        throw new IllegalStateException(
            "Cannot comment single files when having commentOnlyChangedFiles set to false");
      }
      this.createSingleFileComments();
    }
    if (!this.commentsProvider.shouldCreateCommentWithAllSingleFileComments()
        && !this.commentsProvider.shouldCreateSingleFileComment()) {
      this.violationsLogger.log(
          INFO,
          "Will not comment because both 'CreateCommentWithAllSingleFileComments' and 'CreateSingleFileComment' is false.");
    }
  }

  private void createCommentWithAllSingleFileComments() {
    final List<String> accumulatedComments =
        getAccumulatedComments(
            this.violations,
            this.files,
            this.commentsProvider.findCommentTemplate().orElse(null),
            this.commentsProvider.getMaxCommentSize());
    for (final String accumulatedComment : accumulatedComments) {
      this.violationsLogger.log(
          INFO,
          "Asking "
              + this.commentsProvider.getClass().getSimpleName()
              + " to create comment with all single file comments.");
      List<Comment> oldComments = this.commentsProvider.getComments();
      oldComments = filterCommentsWithContent(oldComments, FINGERPRINT_ACC);
      final List<Comment> alreadyMadeComments =
          filterCommentsWithContent(oldComments, accumulatedComment);

      this.removeOldCommentsThatAreNotStillReported(oldComments, alreadyMadeComments);

      if (this.violations.isEmpty()) {
        this.violationsLogger.log(Level.INFO, "No violations to comment");
        return;
      }

      final boolean commentHasNotBeenMade = alreadyMadeComments.isEmpty();
      if (commentHasNotBeenMade) {
        this.commentsProvider.createComment(accumulatedComment);
      }
    }
  }

  private void createSingleFileComments() {
    List<Comment> oldComments = this.commentsProvider.getComments();
    oldComments = filterCommentsWithContent(oldComments, FINGERPRINT);
    oldComments = filterCommentsWithoutContent(oldComments, FINGERPRINT_ACC);
    this.violationsLogger.log(
        INFO, "Asking " + this.commentsProvider.getClass().getSimpleName() + " to comment:");

    final ViolationComments alreadyMadeComments =
        getViolationComments(oldComments, this.violations);

    this.removeOldCommentsThatAreNotStillReported(oldComments, alreadyMadeComments.getComments());

    for (final Violation violation : this.violations) {
      final boolean violationCommentExistsSinceBefore =
          alreadyMadeComments.getViolations().contains(violation);
      if (violationCommentExistsSinceBefore) {
        continue;
      }
      final Optional<ChangedFile> changedFile = findChangedFile(this.files, violation);
      if (changedFile.isPresent()) {
        final String commentTemplate = this.commentsProvider.findCommentTemplate().orElse(null);
        final String singleFileCommentContent =
            createSingleFileCommentContent(changedFile.get(), violation, commentTemplate);
        this.violationsLogger.log(
            INFO,
            violation.getReporter()
                + " "
                + violation.getSeverity()
                + " "
                + violation.getRule()
                + " "
                + changedFile.get().getFilename()
                + " "
                + violation.getStartLine()
                + " "
                + violation.getSource());
        this.commentsProvider.createSingleFileComment(
            changedFile.get(), violation.getStartLine(), singleFileCommentContent);
      }
    }
  }

  private void removeOldCommentsThatAreNotStillReported(
      final List<Comment> oldComments, final List<Comment> comments) {
    if (!this.commentsProvider.shouldKeepOldComments()) {
      final List<Comment> existingWithoutViolation = new ArrayList<>();
      existingWithoutViolation.addAll(oldComments);
      existingWithoutViolation.removeAll(comments);
      this.commentsProvider.removeComments(existingWithoutViolation);
    }
  }

  private Set<Violation> filterChanged(
      final List<ChangedFile> files, final Set<Violation> mixedViolations) {
    final String changedFiles =
        files //
            .stream() //
            .map((f) -> f.getFilename()) //
            .sorted() //
            .collect(joining("\n  "));
    this.violationsLogger.log(INFO, "Files changed:\n  " + changedFiles);

    final String violationFiles =
        mixedViolations //
            .stream() //
            .map((f) -> f.getFile()) //
            .distinct() //
            .sorted() //
            .collect(joining("\n  "));
    this.violationsLogger.log(INFO, "Files with violations:\n  " + violationFiles);

    final Set<Violation> isChanged = new TreeSet<>();
    final Set<String> included = new TreeSet<>();
    final Set<String> notIncludedUntouched = new TreeSet<>();
    final Set<String> notIncludedNotChanged = new TreeSet<>();
    for (final Violation violation : mixedViolations) {
      final Optional<ChangedFile> file = findChangedFile(files, violation);
      final String violationFile = violation.getFile() + " " + violation.getStartLine();
      if (file.isPresent()) {
        final boolean shouldComment =
            this.commentsProvider.shouldComment(file.get(), violation.getStartLine());
        if (shouldComment) {
          isChanged.add(violation);
          included.add(violationFile);
        } else {
          notIncludedUntouched.add(violationFile);
        }
      } else {
        notIncludedNotChanged.add(violationFile);
      }
    }

    if (!included.isEmpty()) {
      this.violationsLogger.log(
          INFO, "Will include violations on:\n  " + included.stream().collect(joining("\n  ")));
    }

    if (!notIncludedUntouched.isEmpty()) {
      this.violationsLogger.log(
          INFO,
          "Will not include violations on changed files because violation reported on untouched lines:\n  "
              + notIncludedUntouched.stream().collect(joining("\n  ")));
    }

    if (!notIncludedNotChanged.isEmpty()) {
      this.violationsLogger.log(
          INFO,
          "Will not include violations on unchanged files:\n  "
              + notIncludedNotChanged.stream().collect(joining("\n  ")));
    }

    return isChanged;
  }
}
