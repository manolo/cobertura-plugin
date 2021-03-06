package hudson.plugins.cobertura;

import hudson.Extension;
import hudson.FilePath;
import hudson.Util;
import hudson.maven.MavenBuild;
import hudson.maven.MavenBuildProxy;
import hudson.maven.MavenModule;
import hudson.maven.MavenReporter;
import hudson.maven.MavenReporterDescriptor;
import hudson.maven.MojoInfo;
import hudson.model.Action;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.plugins.cobertura.renderers.SourceCodePainter;
import hudson.plugins.cobertura.renderers.SourceEncoding;
import hudson.plugins.cobertura.targets.CoverageResult;

import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.component.configurator.ComponentConfigurationException;
import org.codehaus.plexus.configuration.xml.XmlPlexusConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * Created by IntelliJ IDEA. User: stephen Date: 17-Nov-2007 Time: 19:08:46
 */
public class MavenCoberturaPublisher extends MavenReporter {

	@Override
	public boolean preExecute(MavenBuildProxy build, MavenProject pom, MojoInfo mojo, BuildListener listener) throws InterruptedException,
			IOException {
		if (isCoberturaReport(mojo)) {
			// tell cobertura:cobertura to generate the XML report
			XmlPlexusConfiguration c = (XmlPlexusConfiguration) mojo.configuration.getChild("formats");
			if (c == null) {
				listener.getLogger().println("[HUDSON] Configuring cobertura-maven-plugin to enable xml reports");
				XmlPlexusConfiguration fmts = new XmlPlexusConfiguration("formats");
				XmlPlexusConfiguration fmt = new XmlPlexusConfiguration("format");
				fmt.setValue("html"); // this is in by default
				fmts.addChild(fmt);
				fmt = new XmlPlexusConfiguration("format");
				fmt.setValue("xml"); // need this
				fmts.addChild(fmt);
				mojo.configuration.addChild(fmts);
			} else {
				XmlPlexusConfiguration[] fmts = (XmlPlexusConfiguration[]) c.getChildren("format");
				boolean xmlConfigured = false;
				for (XmlPlexusConfiguration fmt : fmts) {
					if ("xml".equalsIgnoreCase(fmt.getValue().trim())) {
						xmlConfigured = true;
						break;
					}
				}
				if (xmlConfigured) {
					listener.getLogger().println("[HUDSON] cobertura-maven-plugin already configured with xml reports enabled");
				} else {
					listener.getLogger().println("[HUDSON] Configuring cobertura-maven-plugin to enable xml reports");
					XmlPlexusConfiguration fmt = new XmlPlexusConfiguration("format");
					fmt.setValue("xml"); // need this
					c.addChild(fmt);
				}
			}
		}
		return true;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean postExecute(final MavenBuildProxy build, final MavenProject pom, final MojoInfo mojo, final BuildListener listener,
			final Throwable error) throws InterruptedException, IOException {
		if (!isCoberturaReport(mojo))
			return true;
	
		boolean haveXMLReport = false;
		File outputDir;
		try {
			outputDir = mojo.getConfigurationValue("outputDirectory", File.class);
			if (!outputDir.exists()) {
				// cobertura-maven-plugin will not generate a report for
				// non-java projects
				return true;
			}

			String[] formats = mojo.getConfigurationValue("formats", String[].class);
			for (String o : formats) {
				if ("xml".equalsIgnoreCase(o.trim())) {
					haveXMLReport = true;
					break;
				}
			}
			if (!haveXMLReport) {
				listener.getLogger().println("[HUDSON] I could not auto-configure the cobertura-maven-plugin to generate xml reports");
				listener.getLogger().println("[HUDSON] If the cobertura plugin needs was configured to generate xml reports, e.g.");
				listener.getLogger().println("[HUDSON]     ...");
				listener.getLogger().println("[HUDSON]     <plugin>");
				listener.getLogger().println("[HUDSON]       <groupId>org.codehaus.mojo</groupId>");
				listener.getLogger().println("[HUDSON]       <artifactId>codehaus-maven-plugin</artifactId>");
				listener.getLogger().println("[HUDSON]       ...");
				listener.getLogger().println("[HUDSON]       <configuration>");
				listener.getLogger().println("[HUDSON]         ...");
				listener.getLogger().println("[HUDSON]         <formats>");
				listener.getLogger().println("[HUDSON]           ...");
				listener.getLogger().println("[HUDSON]           <format>xml</format> <!-- ensure this format is present -->");
				listener.getLogger().println("[HUDSON]           ...");
				listener.getLogger().println("[HUDSON]         </formats>");
				listener.getLogger().println("[HUDSON]         ...");
				listener.getLogger().println("[HUDSON]       </configuration>");
				listener.getLogger().println("[HUDSON]       ...");
				listener.getLogger().println("[HUDSON]     </plugin>");
				listener.getLogger().println("[HUDSON]     ...");
				listener.getLogger().println("[HUDSON] Code coverage reports would be enabled");
				build.setResult(Result.UNSTABLE);
				return true;
			}
		} catch (ComponentConfigurationException e) {
			e.printStackTrace(listener.fatalError("Unable to obtain configuration from cobertura mojo"));
			build.setResult(Result.UNSTABLE);
			return true;
		}

		File reportFile = new File(outputDir, "coverage.xml");
		if (!reportFile.exists()) {
			listener.getLogger().println("[HUDSON] Cobertura report not generated (probably this module is not a java module)");
			return true;
		}

		FilePath reportFilePath = new FilePath(reportFile);

		FilePath target = build.getRootDir();

		try {
			target.mkdirs();
			listener.getLogger().println("[HUDSON] Recording coverage results");
			reportFilePath.copyTo(target.child("coverage.xml"));
		} catch (IOException e) {
			Util.displayIOException(e, listener);
			e.printStackTrace(listener.fatalError("Unable to copy " + reportFilePath + " to " + target));
			build.setResult(Result.FAILURE);
		}

		CoverageResult result = null;
		Set<String> sourcePaths = new HashSet<String>();

		try {
			result = CoberturaCoverageParser.parse(reportFile, null, sourcePaths);
		} catch (IOException e) {
			Util.displayIOException(e, listener);
			e.printStackTrace(listener.fatalError("Unable to parse " + reportFilePath));
			build.setResult(Result.FAILURE);
		}

		if (result != null) {
			result.setOwner(null);
			final FilePath paintedSourcesPath = build.getProjectRootDir().child("cobertura");
			paintedSourcesPath.mkdirs();
			// Get System default encoding;
			SourceEncoding encoding = SourceEncoding.getEncoding(System.getProperty("file.encoding"));
			SourceCodePainter painter = new SourceCodePainter(paintedSourcesPath, sourcePaths, result.getPaintedSources(), listener,
					encoding);

			new FilePath(pom.getBasedir()).act(painter);
			if (!build.execute(new MavenCoberturaActionAdder(listener))) {
				listener.getLogger().println("[HUDSON] Unable to add link to cobertura results");
				build.setResult(Result.FAILURE);
				return true;
			}

		} else {
			listener.getLogger().println("[HUDSON] Unable to parse coverage results.");
			build.setResult(Result.FAILURE);
			return true;
		}

		build.registerAsProjectAction(this);

		return true;
	}

	private boolean isCoberturaReport(MojoInfo mojo) {
		if (!mojo.pluginName.matches("org.codehaus.mojo", "cobertura-maven-plugin"))
			return false;

		if (!mojo.getGoal().equals("cobertura"))
			return false;
		return true;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Action getProjectAction(MavenModule project) {
		return new CoberturaProjectAction(project);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public MavenReporterDescriptor getDescriptor() {
		return DESCRIPTOR;
	}

	/**
	 * Descriptor should be singleton.
	 */
	@Extension
	public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

	public static final class DescriptorImpl extends MavenReporterDescriptor {
		/**
		 * Do not instantiate DescriptorImpl.
		 */
		private DescriptorImpl() {
			super(MavenCoberturaPublisher.class);
		}

		/**
		 * {@inheritDoc}
		 */
		public String getDisplayName() {
			return Messages.MavenCoberturaPublisher_displayName();
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public MavenReporter newAutoInstance(MavenModule mavenModule) {
			return new MavenCoberturaPublisher();
		}
	}

	private static final long serialVersionUID = 1L;

	private static class MavenCoberturaActionAdder implements MavenBuildProxy.BuildCallable<Boolean, IOException> {
		private static final long serialVersionUID = -5470450037371279762L;
		@SuppressWarnings("unused")
		private final BuildListener listener;

		public MavenCoberturaActionAdder(BuildListener listener) {
			this.listener = listener;
		}

		public Boolean call(MavenBuild build) throws IOException {
			try {
				CoberturaBuildAction cba = build.getAction(CoberturaBuildAction.class);
				if (cba == null) {
					File cvgxml = new File(build.getRootDir(), "coverage.xml");
					CoverageResult result = CoberturaCoverageParser.parse(cvgxml, null, new HashSet<String>());
					result.setOwner(build);

					CoberturaBuildAction o = CoberturaBuildAction.load(build, result, null, null, false);
					build.getActions().add(o);
				} else {
					return false;
				}
			} catch (NullPointerException e) {
				return false;
			}
			return true;
		}
	}
}
