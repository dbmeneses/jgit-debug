package org.sonarsource.jgit.debug;

import java.io.IOException;
import java.nio.file.Path;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;

public class JGitUtils {

  private JGitUtils() {
  }

  public static Repository buildRepository(Path basedir) {
    try {
      Repository repo = GitScmProvider.getVerifiedRepositoryBuilder(basedir).build();
      try (ObjectReader objReader = repo.getObjectDatabase().newReader()) {
        // SONARSCGIT-2 Force initialization of shallow commits to avoid later concurrent modification issue
        objReader.getShallowCommits();
        return repo;
      }
    } catch (IOException e) {
      throw new IllegalStateException("Unable to open Git repository", e);
    }
  }
}
