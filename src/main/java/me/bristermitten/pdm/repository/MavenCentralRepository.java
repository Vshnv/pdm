package me.bristermitten.pdm.repository;

import me.bristermitten.pdm.DependencyManager;
import me.bristermitten.pdm.http.HTTPManager;

public final class MavenCentralRepository extends MavenRepository
{

    public static final String MAVEN_CENTRAL_ALIAS = "maven-central";
    private static final String MAVEN_CENTRAL_URL = "https://repo1.maven.org/maven2";

    public MavenCentralRepository(HTTPManager httpManager, DependencyManager manager)
    {
        super(MAVEN_CENTRAL_URL, httpManager, manager);
    }

}
