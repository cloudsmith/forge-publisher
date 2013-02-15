/**
 * Copyright (c) 2012 Cloudsmith Inc. and other contributors, as listed below.
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

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;
import org.cloudsmith.geppetto.common.os.StreamUtil;
import org.cloudsmith.geppetto.forge.ForgeFactory;
import org.cloudsmith.geppetto.forge.impl.MetadataImpl;
import org.cloudsmith.geppetto.forge.util.JsonUtils;
import org.cloudsmith.geppetto.forge.v2.Forge;
import org.cloudsmith.geppetto.forge.v2.client.ForgePreferences;
import org.cloudsmith.geppetto.forge.v2.client.ForgePreferencesBean;
import org.cloudsmith.geppetto.forge.v2.model.Metadata;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;

/**
 * Goal which performs basic validation.
 */
public abstract class AbstractForgeMojo extends AbstractMojo {
	static final String BUILD_DIR = ".geppetto";

	static final Pattern DEFAULT_EXCLUDES_PATTERN;

	static final String IMPORTED_MODULES_ROOT = "importedModules";

	static final Charset UTF_8 = Charset.forName("UTF-8");

	static {
		int top = MetadataImpl.DEFAULT_EXCLUDES.length;
		String[] excludes = new String[top + 1];
		System.arraycopy(MetadataImpl.DEFAULT_EXCLUDES, 0, excludes, 0, top);
		excludes[top] = BUILD_DIR;
		DEFAULT_EXCLUDES_PATTERN = MetadataImpl.compileExcludePattern(excludes);
	}

	@Parameter(property = "forge.modules.root", required = true)
	private File modulesRoot;

	/**
	 * The ClientID to use when performing retrieval of OAuth token. This
	 * parameter is only used when the OAuth token is not provided.
	 */
	@Parameter(property = "forge.oauth.clientID")
	private String clientID = "6ae715305e4c5fede6ee";

	/**
	 * The ClientSecret to use when performing retrieval of OAuth token. This
	 * parameter is only used when the OAuth token is not provided.
	 */
	@Parameter(property = "forge.oauth.clientSecret")
	private String clientSecret = "89a983f88b7961df087cea1a8a8ee8255a7482bf";

	/**
	 * The login name. Not required when the OAuth token is provided.
	 */
	@Parameter(property = "forge.login")
	private String login;

	/**
	 * The OAuth token to use for authentication. If it is provided, then the
	 * login and password does not have to be provided.
	 */
	@Parameter(property = "forge.auth.token")
	private String oauthToken;

	/**
	 * The password. Not required when the OAuth token is provided.
	 */
	@Parameter(property = "forge.password")
	private String password;

	/**
	 * The service URL of the Puppet Forge server
	 */
	@Parameter(property = "forge.serviceURL", required = true)
	private String serviceURL;

	private ForgePreferencesBean forgePreferences;

	private transient File buildDir;

	private transient Forge forge;

	private transient Logger log;

	public void execute() throws MojoExecutionException, MojoFailureException {
		Diagnostic diagnostic = new Diagnostic();
		try {
			log = LoggerFactory.getLogger(getClass());
			invoke(diagnostic);
		}
		catch(RuntimeException e) {
			throw new MojoExecutionException("Internal exception while performing " + getActionName(), e);
		}
		catch(Exception e) {
			throw new MojoFailureException(getActionName() + " failed", e);
		}
		logDiagnostic(null, diagnostic);
		if(diagnostic.getSeverity() == Diagnostic.ERROR)
			throw new MojoFailureException(getActionName() + " failed");
	}

	private boolean findModuleFiles(File[] files, List<File> moduleFiles) {
		if(files != null) {
			int idx = files.length;
			while(--idx >= 0) {
				String name = files[idx].getName();
				if("Modulefile".equals(name) || "metadata.json".equals(name))
					return true;
			}

			idx = files.length;
			while(--idx >= 0) {
				File file = files[idx];
				String name = file.getName();
				if(DEFAULT_EXCLUDES_PATTERN.matcher(name).matches())
					continue;

				if(findModuleFiles(file.listFiles(), moduleFiles)) {
					getLog().debug("Found module in " + file.getAbsolutePath());
					moduleFiles.add(file);
				}
			}
		}
		return false;
	}

	protected List<File> findModuleRoots() {
		// Scan for valid directories containing "Modulefile" files.

		getLog().debug("Scanning " + modulesRoot.getAbsolutePath() + " for Modulefile files");
		List<File> moduleRoots = new ArrayList<File>();
		if(findModuleFiles(modulesRoot.listFiles(), moduleRoots)) {
			// The repository is a module in itself
			getLog().debug("Found module in " + modulesRoot.getAbsolutePath());
			moduleRoots.add(modulesRoot);
		}
		return moduleRoots;
	}

	protected abstract String getActionName();

	protected synchronized File getBuildDir() {
		if(buildDir == null)
			buildDir = new File(modulesRoot, BUILD_DIR);
		return buildDir;
	}

	protected synchronized Forge getForge() {
		if(forge == null)
			forge = new Forge(getForgePreferences());
		return forge;
	}

	protected synchronized ForgePreferences getForgePreferences() {
		if(forgePreferences == null) {
			forgePreferences = new ForgePreferencesBean();
			if(!serviceURL.endsWith("/"))
				serviceURL += "/";
			forgePreferences.setBaseURL(serviceURL + "v2/");
			forgePreferences.setOAuthURL(serviceURL + "oauth/token");
			forgePreferences.setOAuthAccessToken(oauthToken);
			forgePreferences.setOAuthClientId(clientID);
			forgePreferences.setOAuthClientSecret(clientSecret);
			forgePreferences.setLogin(login);
			forgePreferences.setPassword(password);
			forgePreferences.setOAuthScopes("");
		}
		return forgePreferences;
	}

	protected Logger getLogger() {
		return log;
	}

	protected Metadata getModuleMetadata(File moduleDirectory) throws IOException {
		StringWriter writer = new StringWriter();
		try {
			Gson gson = JsonUtils.getGSon();
			gson.toJson(ForgeFactory.eINSTANCE.createForgeService().loadModule(moduleDirectory), writer);
		}
		finally {
			StreamUtil.close(writer);
		}
		Gson gson = getForge().createGson();
		return gson.fromJson(writer.toString(), Metadata.class);
	}

	protected File getModulesRoot() {
		return modulesRoot;
	}

	protected String getRelativePath(File file) {
		IPath rootPath = Path.fromOSString(modulesRoot.getAbsolutePath());
		IPath path = Path.fromOSString(file.getAbsolutePath());
		IPath relative = path.makeRelativeTo(rootPath);
		return relative.toPortableString();
	}

	protected abstract void invoke(Diagnostic result) throws Exception;

	private void logDiagnostic(String indent, Diagnostic diag) {
		if(diag == null)
			return;

		String msg = diag.getMessage();
		if(indent != null)
			msg = indent + msg;

		if(msg != null) {
			msg = diag.getType().name() + ": " + msg;
			switch(diag.getSeverity()) {
				case Diagnostic.DEBUG:
					getLogger().debug(msg);
					break;
				case Diagnostic.WARNING:
					getLogger().warn(msg);
					break;
				case Diagnostic.FATAL:
				case Diagnostic.ERROR:
					getLogger().error(msg);
					break;
				default:
					getLogger().info(msg);
			}
			if(indent == null)
				indent = "  ";
			else
				indent = indent + "  ";
		}

		for(Diagnostic child : diag.getChildren())
			logDiagnostic(indent, child);
	}
}
