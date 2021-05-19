package org.sonarsource.jgit.debug;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;

public class App {

  public static void main(String[] args) {
    if (args.length != 1) {
      System.out.println("Expected target branch name");
      return;
    }

    String target = args[0];
    Path path = Paths.get("").toAbsolutePath();
    System.out.println("Current path " + path);
    GitScmProvider provider = new GitScmProvider();

    System.out.println("sha1: " + provider.revisionId(path));
    Set<Path> modifiedFiles = provider.branchChangedFiles(target, path);
    System.out.println("Changed files: " + modifiedFiles.size());
    for (Path p : modifiedFiles) {
      System.out.println("   " + p);
    }

    System.out.println("----- Getting modified lines");
     provider.branchChangedLines(target, path);

    System.out.println("----- Getting modified lines with original method");
    Map<Path, Set<Integer>> modifiedLines = provider.branchChangedLinesOriginal(target, path, modifiedFiles);

    System.out.println("Files with modified lines: " + modifiedLines.size());
    for (Map.Entry<Path, Set<Integer>> e : modifiedLines.entrySet()) {
      System.out.println("   " + e.getKey() + ":  " + e.getValue());
    }
  }
}
