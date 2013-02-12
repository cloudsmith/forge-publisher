package org.cloudsmith.geppetto.forge.maven.plugin;

import java.io.File;

import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.plugin.Mojo;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.PluginParameterExpressionEvaluator;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.testing.AbstractMojoTestCase;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.codehaus.plexus.PlexusTestCase;
import org.codehaus.plexus.component.configurator.ComponentConfigurationException;
import org.codehaus.plexus.component.configurator.ComponentConfigurator;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluator;
import org.codehaus.plexus.configuration.PlexusConfiguration;
import org.codehaus.plexus.configuration.xml.XmlPlexusConfiguration;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RunWith(JUnit4.class)
public class ValidateTest extends AbstractMojoTestCase {
	private static Logger log = LoggerFactory.getLogger(ValidateTest.class);

	@Override
	protected Mojo lookupConfiguredMojo(MavenSession session, MojoExecution execution) throws Exception,
			ComponentConfigurationException {
		MavenProject project = session.getCurrentProject();
		MojoDescriptor mojoDescriptor = execution.getMojoDescriptor();
		Mojo mojo = (Mojo) lookup(mojoDescriptor.getRole(), mojoDescriptor.getRoleHint());

		Xpp3Dom configuration = null;
		Plugin plugin = project.getPlugin(mojoDescriptor.getPluginDescriptor().getPluginLookupKey());
		if(plugin != null) {
			String goal = execution.getGoal();
			configuration = (Xpp3Dom) plugin.getConfiguration();
			for(PluginExecution pe : plugin.getExecutions()) {
				if(pe.getGoals().contains(goal)) {
					Xpp3Dom execConfig = (Xpp3Dom) pe.getConfiguration();
					if(execConfig != null) {
						if(configuration == null)
							configuration = execConfig;
						else
							configuration = Xpp3Dom.mergeXpp3Dom(execConfig, configuration);
					}
					break;
				}
			}
		}
		if(configuration == null)
			configuration = new Xpp3Dom("configuration");

		PlexusConfiguration pluginConfiguration = new XmlPlexusConfiguration(configuration);
		ComponentConfigurator configurator = getContainer().lookup(ComponentConfigurator.class, "basic");

		ExpressionEvaluator evaluator = new PluginParameterExpressionEvaluator(session, execution);
		configurator.configureComponent(mojo, pluginConfiguration, evaluator, getContainer().getContainerRealm());

		return mojo;
	}

	@Override
	@Before
	public void setUp() throws Exception {
		super.setUp();
	}

	@Override
	@After
	public void tearDown() throws Exception {
		super.tearDown();
	}

	@Test
	public void validate() throws Exception {
		log.info("my.property = " + System.getProperty("my.property"));
		log.info("basedir = " + PlexusTestCase.getBasedir());

		File pom = getTestFile("src/test/resources/unit/publisher/pom.xml");
		Assert.assertNotNull(pom);
		Assert.assertTrue(pom.exists());

		MavenExecutionRequest request = new DefaultMavenExecutionRequest();
		MavenProject project = lookup(ProjectBuilder.class).build(pom, request.getProjectBuildingRequest()).getProject();

		MavenSession session = newMavenSession(project);
		Validate validate = (Validate) lookupConfiguredMojo(session, newMojoExecution("validate"));
		Assert.assertNotNull(validate);
		validate.execute();

		Publish publish = (Publish) lookupConfiguredMojo(session, newMojoExecution("publish"));
		Assert.assertNotNull(publish);
		publish.execute();
	}
}
