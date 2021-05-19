package org.sonarsource.jgit.debug;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import javax.annotation.CheckForNull;

import static java.lang.String.format;

public class GitScmProvider {
  private static final String COULD_NOT_FIND_REF = "Could not find ref '%s' in refs/heads, refs/remotes, refs/remotes/upstream or refs/remotes/origin";

  public boolean supports(File baseDir) {
    RepositoryBuilder builder = new RepositoryBuilder().findGitDir(baseDir);
    return builder.getGitDir() != null;
  }

  @CheckForNull
  public Set<Path> branchChangedFiles(String targetBranchName, Path rootBaseDir) {
    try (Repository repo = buildRepo(rootBaseDir)) {
      Ref targetRef = resolveTargetRef(targetBranchName, repo);
      if (targetRef == null) {
        System.out.println(format(COULD_NOT_FIND_REF
          + ". You may see unexpected issues and changes. "
          + "Please make sure to fetch this ref before pull request analysis and refer to"
          + " <a href=\"/documentation/analysis/scm-integration/\" target=\"_blank\">the documentation</a>.", targetBranchName));
        return null;
      }

      if (isDiffAlgoInvalid(repo.getConfig())) {
        System.out.println("The diff algorithm configured in git is not supported. "
          + "No information regarding changes in the branch will be collected, which can lead to unexpected results.");
        return null;
      }

      Optional<RevCommit> mergeBaseCommit = findMergeBase(repo, targetRef);
      if (!mergeBaseCommit.isPresent()) {
        System.out.println("No merge base found between HEAD and " + targetRef.getName());
        return null;
      }
      AbstractTreeIterator mergeBaseTree = prepareTreeParser(repo, mergeBaseCommit.get());

      // we compare a commit with HEAD, so no point ignoring line endings (it will be whatever is committed)
      try (Git git = newGit(repo)) {
        List<DiffEntry> diffEntries = git.diff()
          .setShowNameAndStatusOnly(true)
          .setOldTree(mergeBaseTree)
          .setNewTree(prepareNewTree(repo))
          .call();

        return diffEntries.stream()
          .filter(diffEntry -> diffEntry.getChangeType() == DiffEntry.ChangeType.ADD || diffEntry.getChangeType() == DiffEntry.ChangeType.MODIFY)
          .map(diffEntry -> repo.getWorkTree().toPath().resolve(diffEntry.getNewPath()))
          .collect(Collectors.toSet());
      }
    } catch (IOException | GitAPIException e) {
      System.out.println(e.getMessage());
      e.printStackTrace();
    }
    return null;
  }

  @CheckForNull
  public void branchChangedLines(String targetBranchName, Path projectBaseDir) {
    try (Repository repo = buildRepo(projectBaseDir)) {
      Ref targetRef = resolveTargetRef(targetBranchName, repo);
      if (targetRef == null) {
        System.out.println(format(COULD_NOT_FIND_REF
          + ". You may see unexpected issues and changes. "
          + "Please make sure to fetch this ref before pull request analysis"
          + " and refer to <a href=\"/documentation/analysis/scm-integration/\" target=\"_blank\">the documentation</a>.", targetBranchName));
        return;
      }

      if (isDiffAlgoInvalid(repo.getConfig())) {
        // we already print a warning when branchChangedFiles is called
        return;
      }

      Optional<RevCommit> mergeBaseCommit = findMergeBase(repo, targetRef);
      if (!mergeBaseCommit.isPresent()) {
        System.out.println("No merge base found between HEAD and " + targetRef.getName());
        return;
      }

      collectChangedLines(repo, mergeBaseCommit.get());
    } catch (Exception e) {
      System.out.println("Failed to get changed lines from git");
      e.printStackTrace();
    }
  }

  private void collectChangedLines(Repository repo, RevCommit mergeBaseCommit) {
    ChangedLinesComputer computer = new ChangedLinesComputer();

    try (DiffFormatter diffFmt = new DiffFormatter(new BufferedOutputStream(computer.receiver()))) {
      // copied from DiffCommand so that we can use a custom DiffFormatter which ignores white spaces.
      diffFmt.setRepository(repo);
      diffFmt.setProgressMonitor(NullProgressMonitor.INSTANCE);

      AbstractTreeIterator mergeBaseTree = prepareTreeParser(repo, mergeBaseCommit);
      List<DiffEntry> diffEntries = diffFmt.scan(mergeBaseTree, new FileTreeIterator(repo));
      diffFmt.format(diffEntries);
      diffFmt.flush();
      diffEntries.forEach(diffEntry -> System.out.println(diffEntry));
    } catch (Exception e) {
      System.out.println("Failed to get changed lines from git");
      e.printStackTrace();
    }
  }

  public Map<Path, Set<Integer>> branchChangedLinesOriginal(String targetBranchName, Path projectBaseDir, Set<Path> changedFiles) {
    try (Repository repo = buildRepo(projectBaseDir)) {
      Ref targetRef = resolveTargetRef(targetBranchName, repo);
      if (targetRef == null) {
        System.out.println(String.format(COULD_NOT_FIND_REF
          + ". You may see unexpected issues and changes. "
          + "Please make sure to fetch this ref before pull request analysis"
          + " and refer to <a href=\"/documentation/analysis/scm-integration/\" target=\"_blank\">the documentation</a>.", targetBranchName));
        return null;
      }

      if (isDiffAlgoInvalid(repo.getConfig())) {
        // we already print a warning when branchChangedFiles is called
        return null;
      }

      // force ignore different line endings when comparing a commit with the workspace
      repo.getConfig().setBoolean("core", null, "autocrlf", true);

      Optional<RevCommit> mergeBaseCommit = findMergeBase(repo, targetRef);
      if (!mergeBaseCommit.isPresent()) {
        System.out.println("No merge base found between HEAD and " + targetRef.getName());
        return null;
      }

      Map<Path, Set<Integer>> changedLines = new HashMap<>();
      Path repoRootDir = repo.getDirectory().toPath().getParent();

      for (Path path : changedFiles) {
        collectChangedLinesOriginal(repo, mergeBaseCommit.get(), changedLines, repoRootDir, path);
      }
      return changedLines;
    } catch (Exception e) {
      System.out.println("Failed to get changed lines from git");
      e.printStackTrace();
    }
    return null;
  }

  private void collectChangedLinesOriginal(Repository repo, RevCommit mergeBaseCommit, Map<Path, Set<Integer>> changedLines, Path repoRootDir, Path changedFile) {
    ChangedLinesComputer computer = new ChangedLinesComputer();

    try (DiffFormatter diffFmt = new DiffFormatter(new BufferedOutputStream(computer.receiver()))) {
      // copied from DiffCommand so that we can use a custom DiffFormatter which ignores white spaces.
      diffFmt.setRepository(repo);
      diffFmt.setProgressMonitor(NullProgressMonitor.INSTANCE);
      diffFmt.setDiffComparator(RawTextComparator.WS_IGNORE_ALL);
      diffFmt.setPathFilter(PathFilter.create(toGitPath(repoRootDir.relativize(changedFile).toString())));

      AbstractTreeIterator mergeBaseTree = prepareTreeParser(repo, mergeBaseCommit);
      List<DiffEntry> diffEntries = diffFmt.scan(mergeBaseTree, new FileTreeIterator(repo));
      diffFmt.format(diffEntries);
      diffFmt.flush();
      diffEntries.stream()
        .filter(diffEntry -> diffEntry.getChangeType() == DiffEntry.ChangeType.ADD || diffEntry.getChangeType() == DiffEntry.ChangeType.MODIFY)
        .findAny()
        .ifPresent(diffEntry -> changedLines.put(changedFile, computer.changedLines()));
    } catch (Exception e) {
      System.out.println("Failed to get changed lines from git for file " + changedFile);
      e.printStackTrace();
    }

    System.out.println("------ Raw diff lines");
    computer.getAllLines().forEach(l -> System.out.println("       " + l));
    System.out.println("--------------------------------------------------");
  }

  @CheckForNull
  private Ref resolveTargetRef(String targetBranchName, Repository repo) throws IOException {
    String localRef = "refs/heads/" + targetBranchName;
    String remotesRef = "refs/remotes/" + targetBranchName;
    String originRef = "refs/remotes/origin/" + targetBranchName;
    String upstreamRef = "refs/remotes/upstream/" + targetBranchName;

    Ref targetRef;
    // Because circle ci destroys the local reference to master, try to load remote ref first.
    // https://discuss.circleci.com/t/git-checkout-of-a-branch-destroys-local-reference-to-master/23781
    targetRef = getFirstExistingRef(repo, localRef, originRef, upstreamRef, remotesRef);
    if (targetRef == null) {
      System.out.println(format(COULD_NOT_FIND_REF, targetBranchName));
    }
    System.out.println("Using ref: " + targetRef);

    return targetRef;
  }

  private static String toGitPath(String path) {
    return path.replaceAll(Pattern.quote(File.separator), "/");
  }

  @CheckForNull
  private static Ref getFirstExistingRef(Repository repo, String... refs) throws IOException {
    Ref targetRef = null;
    for (String ref : refs) {
      targetRef = repo.exactRef(ref);
      if (targetRef != null) {
        break;
      }
    }
    return targetRef;
  }

  @CheckForNull
  public String revisionId(Path path) {
    RepositoryBuilder builder = getVerifiedRepositoryBuilder(path);
    try {
      Ref head = getHead(builder.build());
      if (head == null || head.getObjectId() == null) {
        // can happen on fresh, empty repos
        return null;
      }
      return head.getObjectId().getName();
    } catch (IOException e) {
      throw new IllegalStateException("I/O error while getting revision ID for path: " + path, e);
    }
  }

  private static boolean isDiffAlgoInvalid(Config cfg) {
    try {
      DiffAlgorithm.getAlgorithm(cfg.getEnum(
        ConfigConstants.CONFIG_DIFF_SECTION, null,
        ConfigConstants.CONFIG_KEY_ALGORITHM,
        DiffAlgorithm.SupportedAlgorithm.HISTOGRAM));
      return false;
    } catch (IllegalArgumentException e) {
      return true;
    }
  }

  private static AbstractTreeIterator prepareNewTree(Repository repo) throws IOException {
    CanonicalTreeParser treeParser = new CanonicalTreeParser();
    try (ObjectReader objectReader = repo.newObjectReader()) {
      Ref head = getHead(repo);
      if (head == null) {
        throw new IOException("HEAD reference not found");
      }
      treeParser.reset(objectReader, repo.parseCommit(head.getObjectId()).getTree());
    }
    return treeParser;
  }

  @CheckForNull
  private static Ref getHead(Repository repo) throws IOException {
    return repo.exactRef("HEAD");
  }

  private static Optional<RevCommit> findMergeBase(Repository repo, Ref targetRef) throws IOException {
    try (RevWalk walk = new RevWalk(repo)) {
      Ref head = getHead(repo);
      if (head == null) {
        throw new IOException("HEAD reference not found");
      }

      walk.markStart(walk.parseCommit(targetRef.getObjectId()));
      walk.markStart(walk.parseCommit(head.getObjectId()));
      walk.setRevFilter(RevFilter.MERGE_BASE);
      RevCommit next = walk.next();
      if (next == null) {
        return Optional.empty();
      }
      RevCommit base = walk.parseCommit(next);
      walk.dispose();
      System.out.println("Merge base sha1: " + base.getName());
      return Optional.of(base);
    }
  }

  AbstractTreeIterator prepareTreeParser(Repository repo, RevCommit commit) throws IOException {
    CanonicalTreeParser treeParser = new CanonicalTreeParser();
    try (ObjectReader objectReader = repo.newObjectReader()) {
      treeParser.reset(objectReader, commit.getTree());
    }
    return treeParser;
  }

  Git newGit(Repository repo) {
    return new Git(repo);
  }

  Repository buildRepo(Path basedir) throws IOException {
    return getVerifiedRepositoryBuilder(basedir).build();
  }

  static RepositoryBuilder getVerifiedRepositoryBuilder(Path basedir) {
    RepositoryBuilder builder = new RepositoryBuilder()
      .findGitDir(basedir.toFile())
      .setMustExist(true);

    if (builder.getGitDir() == null) {
      throw new IllegalStateException("Not inside a Git work tree: " + basedir);
    }
    return builder;
  }
}
