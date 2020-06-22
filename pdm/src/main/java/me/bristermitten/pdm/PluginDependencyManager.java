package me.bristermitten.pdm;

import com.google.common.io.CharStreams;
import me.bristermitten.pdm.dependency.Dependency;
import me.bristermitten.pdm.dependency.JSONDependencies;
import me.bristermitten.pdm.repository.JarRepository;
import me.bristermitten.pdm.repository.MavenRepository;
import me.bristermitten.pdm.util.Constants;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public final class PluginDependencyManager
{

    @NotNull
    private final Plugin managing;
    @NotNull
    private final DependencyManager manager;

    @NotNull
    private final Set<Dependency> requiredDependencies = new HashSet<>();

    public PluginDependencyManager(@NotNull final Plugin managing)
    {
        this.managing = managing;
        manager = new DependencyManager(managing);

        loadDependenciesFromFile();
    }

    public void addRequiredDependency(@NotNull final Dependency dependency)
    {
        requiredDependencies.add(dependency);
    }

    private void loadDependenciesFromFile()
    {
        final InputStream dependenciesResource = managing.getResource("dependencies.json");
        if (dependenciesResource == null)
        {
            return;
        }
        final String json;
        try
        {
            //noinspection UnstableApiUsage
            json = CharStreams.toString(new InputStreamReader(dependenciesResource));
        }
        catch (IOException e)
        {
            managing.getLogger().log(Level.WARNING, "Could not read dependencies.json", e);
            return;
        }

        final JSONDependencies jsonDependencies = Constants.GSON.fromJson(json, JSONDependencies.class);
        if (jsonDependencies == null)
        {
            managing.getLogger().warning("jsonDependencies was null - Invalid JSON?");
            return;
        }
        final Map<String, String> repositories = jsonDependencies.getRepositories();
        if (repositories != null)
        {
            repositories.forEach((alias, repo) -> {
                final JarRepository existing = manager.getRepositoryManager().getByName(alias);
                if (existing != null)
                {
                    managing.getLogger().fine(() -> "Will not redefine repository " + alias);
                    return;
                }
                final MavenRepository mavenRepository = new MavenRepository(repo, manager.getManager(), manager);
                manager.getRepositoryManager().addRepository(alias, mavenRepository);

                managing.getLogger().fine(() -> "Made new repository named " + alias);
            });
        }

        jsonDependencies.getDependencies().forEach(dao -> {
            final Dependency dependency = dao.toDependency(manager.getRepositoryManager());
            addRequiredDependency(dependency);
        });
    }

    @NotNull
    public CompletableFuture<Void> loadAllDependencies()
    {
        managing.getLogger().info("Loading Dependencies, please wait...");
        return CompletableFuture.allOf(requiredDependencies.stream()
                .map(manager::downloadAndLoad)
                .toArray(CompletableFuture[]::new))
                .thenRun(() -> managing.getLogger().info("Done!"));
    }
}