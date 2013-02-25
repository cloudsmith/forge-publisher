/**
 * Copyright (c) 2011 Cloudsmith Inc. and other contributors, as listed below.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *   Cloudsmith
 * 
 */
package org.cloudsmith.geppetto.forge.maven.plugin;

import java.io.IOException;
import java.util.Properties;

import org.cloudsmith.geppetto.forge.v2.Forge;
import org.cloudsmith.geppetto.forge.v2.client.ForgePreferencesBean;
import org.cloudsmith.geppetto.forge.v2.service.ModuleService;
import org.cloudsmith.geppetto.forge.v2.service.ModuleTemplate;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class SetupTestMojo extends AbstractForgeTestMojo {
	private Forge forge;

	@Test
	public void createInitialModules() throws IOException {
		ModuleService moduleService = forge.createModuleService();
		ModuleTemplate template = new ModuleTemplate();
		template.setName("test_module_a");
		template.setDescription("The module test_module_a is an integration test artifact");
		moduleService.create(template);

		template.setName("test_module_b");
		template.setDescription("The module test_module_b is an integration test artifact");
		moduleService.create(template);

		template.setName("test_module_c");
		template.setDescription("The module test_module_c is an integration test artifact");
		moduleService.create(template);
	}

	@Override
	@Before
	public void setUp() throws Exception {
		super.setUp();
		ForgePreferencesBean forgePrefs = new ForgePreferencesBean();
		forgePrefs.setLogin(System.getProperty("forge.login"));
		forgePrefs.setPassword(System.getProperty("forge.password"));

		Properties props = AbstractForgeMojo.readForgeProperties();
		forgePrefs.setOAuthClientId(props.getProperty("forge.oauth.clientID"));
		forgePrefs.setOAuthClientSecret(props.getProperty("forge.oauth.clientSecret"));
		String baseURL = System.getProperty("forge.base.url");
		forgePrefs.setBaseURL(baseURL + "v2/");
		forgePrefs.setOAuthURL(baseURL + "oauth/token");
		forge = new Forge(forgePrefs);
	}
}
