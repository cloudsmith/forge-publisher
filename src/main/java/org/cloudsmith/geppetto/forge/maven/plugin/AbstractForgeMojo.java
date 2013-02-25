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
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;
import org.cloudsmith.geppetto.common.os.StreamUtil;
import org.cloudsmith.geppetto.forge.ForgeFactory;
import org.cloudsmith.geppetto.forge.ForgeService;
import org.cloudsmith.geppetto.forge.impl.MetadataImpl;
import org.cloudsmith.geppetto.forge.util.JsonUtils;
import org.cloudsmith.geppetto.forge.v2.Forge;
import org.cloudsmith.geppetto.forge.v2.client.ForgePreferences;
import org.cloudsmith.geppetto.forge.v2.client.ForgePreferencesBean;
import org.cloudsmith.geppetto.forge.v2.model.Metadata;
import org.cloudsmith.geppetto.forge.v2.model.QName;
import org.cloudsmith.geppetto.validation.DiagnosticType;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

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

	private static boolean isNull(String field) {
		if(field == null)
			return true;

		field = field.trim();
		if(field.length() == 0)
			return true;

		return "null".equals(field);
	}

	public static Properties readForgeProperties() throws IOException {
		Properties props = new Properties();
		InputStream inStream = AbstractForgeMojo.class.getResourceAsStream("/forge.properties");
		if(inStream == null)
			throw new FileNotFoundException("Resource forge.properties");
		try {
			props.load(inStream);
			return props;
		}
		finally {
			inStream.close();
		}
	}

	@Parameter(property = "forge.modules.root", required = true)
	private File modulesRoot;

	/**
	 * The ClientID to use when performing retrieval of OAuth token. This
	 * parameter is only used when the OAuth token is not provided.
	 */
	private String clientID;

	/**
	 * The ClientSecret to use when performing retrieval of OAuth token. This
	 * parameter is only used when the OAuth token is not provided.
	 */
	private String clientSecret;

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

	public AbstractForgeMojo() {
		try {
			Properties props = readForgeProperties();
			clientID = props.getProperty("forge.oauth.clientID");
			clientSecret = props.getProperty("forge.oauth.clientSecret");
		}
		catch(IOException e) {
			// Not able to read properties
			throw new RuntimeException(e);
		}
	}

	public void execute() throws MojoExecutionException, MojoFailureException {
		Diagnostic diagnostic = new Diagnostic();
		try {
			if(modulesRoot == null)
				throw new MojoExecutionException("Missing required configuration parameter: 'modulesRoot'");
			if(serviceURL == null)
				throw new MojoExecutionException("Missing required configuration parameter: 'serviceURL'");
			invoke(diagnostic);
		}
		catch(JsonParseException e) {
			throw new MojoFailureException(getActionName() + " failed: Invalid Json: " + e.getMessage(), e);
		}
		catch(RuntimeException e) {
			throw new MojoExecutionException("Internal exception while performing " + getActionName() + ": " +
					e.getMessage(), e);
		}
		catch(Exception e) {
			throw new MojoFailureException(getActionName() + " failed: " + e.getMessage(), e);
		}
		logDiagnostic(null, diagnostic);
		if(diagnostic.getSeverity() == Diagnostic.ERROR)
			throw new MojoFailureException(diagnostic.getErrorText());
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
		if(log == null)
			log = LoggerFactory.getLogger(getClass());
		return log;
	}

	protected Metadata getModuleMetadata(File moduleDirectory, Diagnostic diag) throws IOException {
		StringWriter writer = new StringWriter();
		try {
			ForgeService forgeService = ForgeFactory.eINSTANCE.createForgeService();
			org.cloudsmith.geppetto.forge.Metadata md;
			Gson gson = JsonUtils.getGSon();
			try {
				md = forgeService.loadJSONMetadata(new File(moduleDirectory, "metadata.json"));
			}
			catch(FileNotFoundException e) {
				md = forgeService.loadModule(moduleDirectory);
			}
			// TODO: User the v2 Metadata throughout.
			gson.toJson(md, writer);
		}
		finally {
			StreamUtil.close(writer);
		}
		Gson gson = getForge().createGson();
		Metadata md = gson.fromJson(writer.toString(), Metadata.class);
		if(isNull(md.getAuthor())) {
			md.setAuthor(null);
			diag.addChild(new Diagnostic(Diagnostic.ERROR, DiagnosticType.GEPPETTO, "Module Author must not be null"));
		}
		if(isNull(md.getVersion())) {
			md.setVersion(null);
			diag.addChild(new Diagnostic(Diagnostic.ERROR, DiagnosticType.GEPPETTO, "Module Version must not be null"));
		}
		QName qname = md.getName();
		if(qname != null) {
			String qual = qname.getQualifier();
			String name = qname.getName();
			if(isNull(qual)) {
				qual = null;
				diag.addChild(new Diagnostic(
					Diagnostic.ERROR, DiagnosticType.GEPPETTO, "Module Qualifier must not be null"));
			}
			if(isNull(name)) {
				name = null;
				diag.addChild(new Diagnostic(Diagnostic.ERROR, DiagnosticType.GEPPETTO, "Module Name must not be null"));
			}
			if(qual == null || name == null) {
				qname = new QName(qual, name);
				md.setName(qname);
			}
		}
		return md;
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

	public void setLogger(Logger log) {
		this.log = log;
	}
}
