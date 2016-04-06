/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIESOR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.aries.tx.control.itests;

import static org.ops4j.pax.exam.CoreOptions.bootClasspathLibrary;
import static org.ops4j.pax.exam.CoreOptions.junitBundles;
import static org.ops4j.pax.exam.CoreOptions.mavenBundle;
import static org.ops4j.pax.exam.CoreOptions.options;
import static org.ops4j.pax.exam.CoreOptions.systemPackage;
import static org.ops4j.pax.exam.CoreOptions.systemProperty;
import static org.ops4j.pax.exam.CoreOptions.when;

import java.io.File;
import java.io.IOException;
import java.util.Dictionary;
import java.util.Hashtable;

import javax.persistence.EntityManager;

import org.apache.aries.itest.AbstractIntegrationTest;
import org.apache.aries.tx.control.itests.entity.Message;
import org.h2.tools.Server;
import org.junit.After;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.Configuration;
import org.ops4j.pax.exam.CoreOptions;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.ProbeBuilder;
import org.ops4j.pax.exam.TestProbeBuilder;
import org.ops4j.pax.exam.junit.PaxExam;
import org.ops4j.pax.exam.spi.reactors.ExamReactorStrategy;
import org.ops4j.pax.exam.spi.reactors.PerClass;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.jdbc.DataSourceFactory;
import org.osgi.service.jpa.EntityManagerFactoryBuilder;
import org.osgi.service.transaction.control.TransactionControl;
import org.osgi.service.transaction.control.jpa.JPAEntityManagerProvider;

@RunWith(PaxExam.class)
@ExamReactorStrategy(PerClass.class)
public abstract class AbstractJPATransactionTest extends AbstractIntegrationTest {

	protected TransactionControl txControl;

	protected EntityManager em;

	private Server server;

	@Before
	public void setUp() throws Exception {
		
		txControl = context().getService(TransactionControl.class, 5000);
		
		server = Server.createTcpServer("-tcpPort", "0");
		server.start();
		
		String jdbcUrl = "jdbc:h2:tcp://127.0.0.1:" + server.getPort() + "/" + getRemoteDBPath();
		
		em = configuredEntityManager(jdbcUrl);
	}

	private String getRemoteDBPath() {
		String fullResourceName = getClass().getName().replace('.', '/') + ".class";
		
		String resourcePath = getClass().getResource(getClass().getSimpleName() + ".class").getPath();
		
		File testClassesDir = new File(resourcePath.substring(0, resourcePath.length() - fullResourceName.length()));
		
		String dbPath = new File(testClassesDir.getParentFile(), "testdb/db1").getAbsolutePath();
		return dbPath;
	}

	private EntityManager configuredEntityManager(String jdbcUrl) throws IOException {
		
		Dictionary<String, Object> props = new Hashtable<>();
		
		props.put(DataSourceFactory.OSGI_JDBC_DRIVER_CLASS, "org.h2.Driver");
		props.put(DataSourceFactory.JDBC_URL, jdbcUrl);
		props.put(EntityManagerFactoryBuilder.JPA_UNIT_NAME, "test-unit");
		
		ConfigurationAdmin cm = context().getService(ConfigurationAdmin.class, 5000);
		
		String pid = "org.apache.aries.tx.control.jpa.local"; 
		
		System.out.println("Configuring connection provider with pid " + pid);
		
		org.osgi.service.cm.Configuration config = cm.createFactoryConfiguration(
				pid, null);
		config.update(props);
		
		return context().getService(JPAEntityManagerProvider.class, 5000).getResource(txControl);
	}
	
	@After
	public void tearDown() {

		try {
			txControl.required(() -> 
				em.createQuery(
						em.getCriteriaBuilder().createCriteriaDelete(Message.class)
				).executeUpdate());
		} catch (Exception e) {
			e.printStackTrace();
		}
		
		
		clearConfiguration();
		
		if(server != null) {
			server.stop();
		}

		em = null;
	}

	private void clearConfiguration() {
		ConfigurationAdmin cm = context().getService(ConfigurationAdmin.class, 5000);
		org.osgi.service.cm.Configuration[] cfgs = null;
		try {
			cfgs = cm.listConfigurations(null);
		} catch (Exception e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
		
		if(cfgs != null) {
			for(org.osgi.service.cm.Configuration cfg : cfgs) {
				try {
					cfg.delete();
				} catch (Exception e) {}
			}
			try {
				Thread.sleep(250);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}

	@ProbeBuilder
	public TestProbeBuilder probeConfiguration(TestProbeBuilder probe) {
	    // makes sure the generated Test-Bundle contains this import!
	    probe.setHeader("Meta-Persistence", "META-INF/persistence.xml");
	    return probe;
	}
	
	@Configuration
	public Option[] localTxConfiguration() {
		String localRepo = System.getProperty("maven.repo.local");
		if (localRepo == null) {
			localRepo = System.getProperty("org.ops4j.pax.url.mvn.localRepository");
		}
		
		return options(junitBundles(), systemProperty("org.ops4j.pax.logging.DefaultServiceLog.level").value("INFO"),
				when(localRepo != null)
				.useOptions(CoreOptions.vmOption("-Dorg.ops4j.pax.url.mvn.localRepository=" + localRepo)),
				mavenBundle("org.apache.aries.testsupport", "org.apache.aries.testsupport.unit").versionAsInProject(),
				localTxControlService(),
				localJpaResourceProviderWithH2(),
				eclipseLink2_3_0(),
				ariesJPA(),
				mavenBundle("org.apache.felix", "org.apache.felix.configadmin").versionAsInProject(),
				mavenBundle("org.ops4j.pax.logging", "pax-logging-api").versionAsInProject(),
				mavenBundle("org.ops4j.pax.logging", "pax-logging-service").versionAsInProject()
				
//				,CoreOptions.vmOption("-Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5005")
				);
	}

	public Option localTxControlService() {
		return CoreOptions.composite(
				mavenBundle("org.apache.felix", "org.apache.felix.coordinator").versionAsInProject(),
				mavenBundle("org.apache.aries.tx-control", "tx-control-service-local").versionAsInProject());
	}

	public Option localJpaResourceProviderWithH2() {
		return CoreOptions.composite(
				mavenBundle("com.h2database", "h2").versionAsInProject(),
				mavenBundle("org.apache.aries.tx-control", "tx-control-provider-jpa-local").versionAsInProject());
	}
	
	public Option eclipseLink2_3_0() {
		return CoreOptions.composite(
				systemPackage("javax.transaction;version=1.1"),
				systemPackage("javax.transaction.xa;version=1.1"),
				bootClasspathLibrary(mavenBundle("org.apache.geronimo.specs", "geronimo-jta_1.1_spec", "1.1.1")),
				mavenBundle("org.eclipse.persistence", "org.eclipse.persistence.jpa", "2.6.0"),
				mavenBundle("org.eclipse.persistence", "org.eclipse.persistence.core", "2.6.0"),
				mavenBundle("org.eclipse.persistence", "org.eclipse.persistence.asm", "2.6.0"),
				mavenBundle("org.eclipse.persistence", "org.eclipse.persistence.antlr", "2.6.0"),
				mavenBundle("org.eclipse.persistence", "org.eclipse.persistence.jpa.jpql", "2.6.0"),
				mavenBundle("org.apache.aries.jpa", "org.apache.aries.jpa.eclipselink.adapter", "2.3.0"));
	}

	public Option ariesJPA() {
		return mavenBundle("org.apache.aries.jpa", "org.apache.aries.jpa.container", "2.3.0");
	}
}