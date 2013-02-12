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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.cloudsmith.geppetto.common.os.StreamUtil.OpenBAStream;
import org.cloudsmith.geppetto.forge.util.TarUtils;
import org.cloudsmith.geppetto.forge.v2.MetadataRepository;
import org.cloudsmith.geppetto.forge.v2.model.Dependency;
import org.cloudsmith.geppetto.forge.v2.model.Metadata;
import org.cloudsmith.geppetto.forge.v2.model.Module;
import org.cloudsmith.geppetto.forge.v2.model.Release;
import org.cloudsmith.geppetto.forge.v2.service.ReleaseService;
import org.cloudsmith.geppetto.pp.dsl.PPStandaloneSetup;
import org.cloudsmith.geppetto.pp.dsl.target.PptpResourceUtil;
import org.cloudsmith.geppetto.pp.dsl.validation.DefaultPotentialProblemsAdvisor;
import org.cloudsmith.geppetto.puppetlint.PuppetLintRunner;
import org.cloudsmith.geppetto.puppetlint.PuppetLintRunner.Issue;
import org.cloudsmith.geppetto.puppetlint.PuppetLintService;
import org.cloudsmith.geppetto.ruby.RubyHelper;
import org.cloudsmith.geppetto.ruby.jrubyparser.JRubyServices;
import org.cloudsmith.geppetto.validation.DetailedDiagnosticData;
import org.cloudsmith.geppetto.validation.DiagnosticType;
import org.cloudsmith.geppetto.validation.FileType;
import org.cloudsmith.geppetto.validation.ValidationOptions;
import org.cloudsmith.geppetto.validation.ValidationServiceFactory;
import org.cloudsmith.geppetto.validation.runner.IEncodingProvider;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.common.util.BasicDiagnostic;
import org.eclipse.emf.common.util.URI;

/**
 * Goal which performs basic validation.
 */
@Mojo(name = "validate")
public class Validate extends AbstractForgeMojo {
	private static int getSeverity(Issue issue) {
		switch(issue.getSeverity()) {
			case ERROR:
				return Diagnostic.ERROR;
			default:
				return Diagnostic.WARNING;
		}
	}

	private static int getSeverity(org.eclipse.emf.common.util.Diagnostic validationDiagnostic) {
		int severity;
		switch(validationDiagnostic.getSeverity()) {
			case org.eclipse.emf.common.util.Diagnostic.ERROR:
				severity = Diagnostic.ERROR;
				break;
			case org.eclipse.emf.common.util.Diagnostic.WARNING:
				severity = Diagnostic.WARNING;
				break;
			case org.eclipse.emf.common.util.Diagnostic.INFO:
				severity = Diagnostic.INFO;
				break;
			default:
				severity = Diagnostic.OK;
		}
		return severity;
	}

	private static String locationLabel(DetailedDiagnosticData detail) {
		int lineNumber = detail.getLineNumber();
		int offset = detail.getOffset();
		int length = detail.getLength();
		StringBuilder builder = new StringBuilder();
		if(lineNumber > 0)
			builder.append(lineNumber);
		else
			builder.append("-");

		if(offset >= 0) {
			builder.append("(");
			builder.append(offset);
			if(length >= 0) {
				builder.append(",");
				builder.append(length);
			}
			builder.append(")");
		}
		return builder.toString();
	}

	/**
	 * Set to <tt>true</tt> to enable validation using puppet-lint
	 */
	@Parameter(property = "forge.enable.lint")
	private boolean enablePuppetLintValidation;

	/**
	 * Set to <tt>false</tt> to disable validation using the Geppetto Validator. It
	 * is <tt>true</tt> by default.
	 */
	@Parameter(property = "forge.enable.geppetto")
	private boolean enableGeppettoValidation = true;

	/**
	 * <p>
	 * <i>Geppetto Specific</i>
	 * </p>
	 * <p>
	 * If this is set to <tt>true</tt> then the validator will make an attempt to resolve and install all dependencies for the modules that are
	 * validated. Dependencies are resolved transitively and unresolved dependencies are considered to be validation errors.
	 * </p>
	 * 
	 * @see #enableGeppettoValidation
	 */
	@Parameter(property = "forge.check.references")
	private boolean checkReferences;

	@Parameter(property = "forge.lint.options")
	private PuppetLintRunner.Option[] puppetLintOptions;

	private Diagnostic convertPuppetLintDiagnostic(File moduleRoot, Issue issue) {
		Diagnostic diagnostic = new Diagnostic();
		diagnostic.setSeverity(getSeverity(issue));
		diagnostic.setMessage(issue.getMessage());
		diagnostic.setType(DiagnosticType.PUPPET_LINT);
		diagnostic.setResourcePath(getRelativePath(new File(moduleRoot, issue.getPath())));
		diagnostic.setLocationLabel(Integer.toString(issue.getLineNumber()));
		return diagnostic;
	}

	private Diagnostic convertValidationDiagnostic(org.eclipse.emf.common.util.Diagnostic validationDiagnostic) {

		Object dataObj = validationDiagnostic.getData().get(0);
		String resourcePath = null;
		String locationLabel = null;
		if(dataObj instanceof DetailedDiagnosticData) {
			DetailedDiagnosticData details = (DetailedDiagnosticData) dataObj;
			resourcePath = details.getFile().getPath();
			if(resourcePath != null && resourcePath.startsWith(BUILD_DIR))
				// We don't care about warnings/errors from imported modules
				return null;
			locationLabel = locationLabel(details);
		}

		Diagnostic diagnostic = new Diagnostic();
		diagnostic.setSeverity(getSeverity(validationDiagnostic));
		diagnostic.setType(DiagnosticType.getByCode(validationDiagnostic.getCode()));
		diagnostic.setMessage(validationDiagnostic.getMessage());
		diagnostic.setResourcePath(resourcePath);
		diagnostic.setLocationLabel(locationLabel);
		return diagnostic;
	}

	private File downloadAndInstall(ReleaseService releaseService, File modulesRoot, Release release,
			Diagnostic diagnostic) throws IOException {
		OpenBAStream content = new OpenBAStream();
		Module module = release.getModule();
		releaseService.download(module.getOwner().getUsername(), module.getName(), release.getVersion(), content);
		File moduleDir = new File(modulesRoot, module.getName());
		TarUtils.unpack(new GZIPInputStream(content.getInputStream()), moduleDir, false);
		return moduleDir;
	}

	private void geppettoValidation(List<File> moduleLocations, Diagnostic result) throws IOException {

		RubyHelper.setRubyServicesFactory(JRubyServices.FACTORY);
		PPStandaloneSetup.doSetup();
		MetadataRepository metadataRepo = getForge().createMetadataRepository();

		List<File> importedModuleLocations = null;
		if(checkReferences) {
			List<Metadata> metadatas = new ArrayList<Metadata>();
			for(File moduleRoot : moduleLocations) {
				Metadata metadata = getModuleMetadata(moduleRoot);
				metadatas.add(metadata);
			}

			Set<Dependency> unresolvedCollector = new HashSet<Dependency>();
			Set<Release> releasesToDownload = resolveDependencies(metadataRepo, metadatas, unresolvedCollector);
			for(Dependency unresolved : unresolvedCollector)
				result.addChild(new Diagnostic(Diagnostic.WARNING, String.format(
					"Unable to resolve dependency: %s:%s", unresolved.getName(),
					unresolved.getVersionRequirement().toString())));

			if(!releasesToDownload.isEmpty()) {
				File importedModulesDir = new File(getBuildDir(), IMPORTED_MODULES_ROOT);
				importedModulesDir.mkdirs();
				importedModuleLocations = new ArrayList<File>();

				ReleaseService releaseService = getForge().createReleaseService();
				for(Release release : releasesToDownload) {
					result.addChild(new Diagnostic(Diagnostic.INFO, "Installing dependent module " +
							release.getFullName() + ':' + release.getVersion()));
					importedModuleLocations.add(downloadAndInstall(releaseService, importedModulesDir, release, result));
				}
			}
			else {
				if(unresolvedCollector.isEmpty())
					result.addChild(new Diagnostic(Diagnostic.INFO, "No addtional dependencies were detected"));
			}
		}
		if(importedModuleLocations == null)
			importedModuleLocations = Collections.emptyList();
		BasicDiagnostic diagnostics = new BasicDiagnostic();

		ValidationOptions options = getValidationOptions(moduleLocations, importedModuleLocations);
		ValidationServiceFactory.createValidationService().validate(
			diagnostics, getModulesRoot(), options,
			importedModuleLocations.toArray(new File[importedModuleLocations.size()]), new NullProgressMonitor());

		for(org.eclipse.emf.common.util.Diagnostic diagnostic : diagnostics.getChildren()) {
			Diagnostic diag = convertValidationDiagnostic(diagnostic);
			if(diag != null)
				result.addChild(diag);
		}
	}

	@Override
	protected String getActionName() {
		return "Validation";
	}

	private ValidationOptions getValidationOptions(List<File> moduleLocations, List<File> importedModuleLocations) {
		ValidationOptions options = new ValidationOptions();
		options.setCheckLayout(true);
		options.setCheckModuleSemantics(true);
		options.setCheckReferences(checkReferences);
		options.setFileType(FileType.PUPPET_ROOT);

		// TODO: Selectable in the UI
		options.setPlatformURI(PptpResourceUtil.getPuppet_2_7_19());

		options.setEncodingProvider(new IEncodingProvider() {
			public String getEncoding(URI file) {
				return UTF_8.name();
			}
		});

		StringBuilder searchPath = new StringBuilder();

		searchPath.append("lib/*:environments/$environment/*");

		for(File moduleLocation : moduleLocations)
			searchPath.append(":" + getRelativePath(moduleLocation) + "/*");

		for(File importedModuleLocation : importedModuleLocations)
			searchPath.append(":" + getRelativePath(importedModuleLocation) + "/*");

		options.setSearchPath(searchPath.toString());
		options.setProblemsAdvisor(new DefaultPotentialProblemsAdvisor());
		return options;
	}

	@Override
	protected void invoke(Diagnostic result) throws IOException {
		List<File> moduleRoots = findModuleRoots();
		if(moduleRoots.isEmpty()) {
			result.addChild(new Diagnostic(Diagnostic.ERROR, "No modules found in repository"));
			return;
		}

		if(enableGeppettoValidation)
			geppettoValidation(moduleRoots, result);

		if(enablePuppetLintValidation)
			lintValidation(moduleRoots, result);
	}

	private void lintValidation(List<File> moduleLocations, Diagnostic result) throws IOException {
		PuppetLintRunner runner = PuppetLintService.getInstance().getPuppetLintRunner();
		getLog().debug("Performing puppet lint validation on all modules");
		if(puppetLintOptions == null)
			puppetLintOptions = new PuppetLintRunner.Option[0];
		for(File moduleRoot : moduleLocations) {
			for(PuppetLintRunner.Issue issue : runner.run(moduleRoot, puppetLintOptions)) {
				Diagnostic diag = convertPuppetLintDiagnostic(moduleRoot, issue);
				if(diag != null)
					result.addChild(diag);
			}
		}
	}

	private Set<Release> resolveDependencies(MetadataRepository metadataRepo, List<Metadata> metadatas,
			Set<Dependency> unresolvedCollector) throws IOException {
		// Resolve missing dependencies
		Set<Dependency> deps = new HashSet<Dependency>();
		for(Metadata metadata : metadatas)
			deps.addAll(metadata.getDependencies());

		// Remove the dependencies that appoints modules that we have in the
		// workspace
		Iterator<Dependency> depsItor = deps.iterator();
		nextDep: while(depsItor.hasNext()) {
			Dependency dep = depsItor.next();
			for(Metadata metadata : metadatas)
				if(dep.matches(metadata)) {
					depsItor.remove();
					continue nextDep;
				}
		}

		// Resolve remaining dependencies
		Set<Release> releasesToDownload = new HashSet<Release>();
		for(Dependency dep : deps)
			releasesToDownload.addAll(metadataRepo.deepResolve(dep, unresolvedCollector));
		return releasesToDownload;
	}
}
