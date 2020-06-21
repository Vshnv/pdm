package me.bristermitten.pdm;

import me.bristermitten.pdm.dependency.Dependency;
import me.bristermitten.pdm.http.HTTPManager;
import me.bristermitten.pdm.repository.JarRepository;
import me.bristermitten.pdm.repository.MavenCentralRepository;
import me.bristermitten.pdm.repository.RepositoryManager;
import me.bristermitten.pdm.repository.SpigotRepository;
import me.bristermitten.pdm.util.FileUtil;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public class DependencyManager
{

    private static final String PDM_DIRECTORY_NAME = "PluginLibraries";

    @NotNull
    private final Plugin managing;

    private final RepositoryManager repositoryManager;

    private final HTTPManager manager;

    private final DependencyLoader loader;
    private final File pdmDirectory;
    private final Map<Dependency, CompletableFuture<File>> downloadsInProgress = new ConcurrentHashMap<>();

    public DependencyManager(@NotNull final Plugin managing)
    {
        this.managing = managing;
        this.manager = new HTTPManager(managing.getLogger());
        this.loader = new DependencyLoader(managing);
        pdmDirectory = new File(managing.getDataFolder().getParentFile(), PDM_DIRECTORY_NAME);

        repositoryManager = new RepositoryManager();
        loadRepositories();
    }

    public RepositoryManager getRepositoryManager()
    {
        return repositoryManager;
    }

    public HTTPManager getManager()
    {
        return manager;
    }

    private void loadRepositories()
    {
        repositoryManager.addRepository(
                MavenCentralRepository.MAVEN_CENTRAL_ALIAS, new MavenCentralRepository(manager, this)
        );
        repositoryManager.addRepository(
                SpigotRepository.SPIGOT_ALIAS, new SpigotRepository(manager, this)
        );
    }

    public CompletableFuture<Void> downloadAndLoad(Dependency dependency)
    {
        CompletableFuture<File> downloaded = download(dependency);
        return downloaded.thenAccept(loader::loadDependency);
    }

    private CompletableFuture<File> download(Dependency dependency)
    {
        CompletableFuture<File> inProgress = downloadsInProgress.get(dependency);
        if (inProgress != null)
        {
            return inProgress;
        }

        FileUtil.createDirectoryIfNotPresent(pdmDirectory);

        File file = new File(pdmDirectory, dependency.getJarName());

        CompletableFuture<File> fileFuture = new CompletableFuture<>();
        downloadsInProgress.put(dependency, fileFuture);

        Collection<JarRepository> toCheck;
        if (dependency.getSourceRepository() != null)
        {
            toCheck = Collections.singleton(dependency.getSourceRepository());
        } else
        {
            toCheck = repositoryManager.getRepositories();
        }
        Set<JarRepository> checked = ConcurrentHashMap.newKeySet();
        AtomicBoolean anyRepoContains = new AtomicBoolean();
        for (JarRepository repo : toCheck)
        {
            if (anyRepoContains.get()) continue;
            repo.contains(dependency).thenAccept(contains -> {
                if (anyRepoContains.get()) return;
                checked.add(repo);
                if (contains == null || !contains)
                {
                    if (dependency.getSourceRepository() != null)
                    {
                        managing.getLogger().info(() -> "Repository " + repo + " did not contain dependency " + dependency + " despite it being the configured repo!");
                    }
                    if (checked.size() == toCheck.size() && !anyRepoContains.get())
                    {
                        managing.getLogger().warning(() -> "No repository found for " + dependency + ", it cannot be downloaded. Other plugins may not function properly.");
                        fileFuture.complete(null);
                    }
                    return;
                }
                anyRepoContains.set(true);
                //Load all transitive dependencies before loading the actual jar
                repo.getTransitiveDependencies(dependency)
                        .thenAccept(transitiveDependencies -> {
                            if (transitiveDependencies.isEmpty())
                            {
                                return;
                            }
                            transitiveDependencies.forEach(transitive -> downloadAndLoad(transitive).join());
                        })
                        .thenApply(v -> downloadToFile(repo, dependency, file))
                        .thenAccept(v2 -> fileFuture.complete(file));
            });
        }
        return fileFuture;
    }

    private synchronized CompletableFuture<Void> downloadToFile(JarRepository repo, Dependency dependency, File file)
    {
        CompletableFuture<Void> future = new CompletableFuture<>();
        if (file.exists())
        {
            future.complete(null);
            return future;
        }

        repo.downloadDependency(dependency).thenAccept(bytes -> {
            try
            {
                Files.copy(new ByteArrayInputStream(bytes), file.toPath());
            }
            catch (IOException e)
            {
                managing.getLogger().log(Level.SEVERE, () -> "Could not copy file for " + dependency + ", threw " + e);
            }
            future.complete(null);
            downloadsInProgress.remove(dependency);
        });
        return future;
    }
}
