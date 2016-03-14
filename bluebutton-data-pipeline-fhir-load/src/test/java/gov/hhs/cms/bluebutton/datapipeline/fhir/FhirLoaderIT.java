package gov.hhs.cms.bluebutton.datapipeline.fhir;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.jdo.PersistenceManager;

import org.datanucleus.api.jdo.JDOPersistenceManagerFactory;
import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.rules.SpringClassRule;
import org.springframework.test.context.junit4.rules.SpringMethodRule;

import com.justdavis.karl.misc.datasources.provisioners.IProvisioningRequest;
import com.justdavis.karl.misc.datasources.provisioners.hsql.HsqlProvisioningRequest;

import gov.hhs.cms.bluebutton.datapipeline.ccw.extract.CcwExtractor;
import gov.hhs.cms.bluebutton.datapipeline.ccw.jdo.CurrentBeneficiary;
import gov.hhs.cms.bluebutton.datapipeline.ccw.test.CcwTestHelper;
import gov.hhs.cms.bluebutton.datapipeline.ccw.test.TearDownAcceptor;
import gov.hhs.cms.bluebutton.datapipeline.desynpuf.SynpufArchive;
import gov.hhs.cms.bluebutton.datapipeline.fhir.load.FhirLoader;
import gov.hhs.cms.bluebutton.datapipeline.fhir.load.FhirResult;
import gov.hhs.cms.bluebutton.datapipeline.fhir.transform.BeneficiaryBundle;
import gov.hhs.cms.bluebutton.datapipeline.fhir.transform.DataTransformer;
import gov.hhs.cms.bluebutton.datapipeline.sampledata.SampleDataLoader;

/**
 * Integration tests for {@link DataTransformer}.
 */
@ContextConfiguration(classes = { SpringConfigForTests.class })
@RunWith(Parameterized.class)
public final class FhirLoaderIT {
	@ClassRule
	public static final SpringClassRule springClassRule = new SpringClassRule();

	@Rule
	public final SpringMethodRule springMethodRule = new SpringMethodRule();

	@Rule
	public final TearDownAcceptor tearDown = new TearDownAcceptor();

	@Inject
	public CcwTestHelper ccwHelper;

	@Parameters
	public static Iterable<Object> createTestParameters() {
		return Arrays.asList(new HsqlProvisioningRequest("tests"));
	}

	@Parameter(0)
	public IProvisioningRequest provisioningRequest;

	/**
	 * Verifies that the entire data pipeline works correctly: all the way from
	 * populating a mock CCW schema with DE-SynPUF sample data through
	 * extracting, transform, and finally loading that data into a live FHIR
	 * server.
	 * 
	 * @throws URISyntaxException
	 *             (won't happen: URI is hardcoded)
	 */
	@Test
	public void loadSynpufData() throws URISyntaxException {
		JDOPersistenceManagerFactory pmf = ccwHelper.provisionMockCcwDatabase(provisioningRequest, tearDown);

		try (PersistenceManager pm = pmf.getPersistenceManager();) {
			// Load the DE-SynPUF sample data and then extract it.
			SampleDataLoader sampleLoader = new SampleDataLoader(pm);
			sampleLoader.loadSampleData(Paths.get(".", "target"));
			CcwExtractor extractor = new CcwExtractor(pm);
			Stream<CurrentBeneficiary> beneficiariesStream = extractor.extractAllBeneficiaries();

			// Transform the data.
			Stream<BeneficiaryBundle> fhirStream = new DataTransformer().transformSourceData(beneficiariesStream);

			// Push the data to FHIR.
			URI fhirServer = new URI("http://localhost:8080/hapi-fhir/baseDstu2");
			LoadAppOptions options = new LoadAppOptions(fhirServer);
			FhirLoader loader = new FhirLoader(options);
			List<FhirResult> results = loader.insertFhirRecords(fhirStream);

			// Verify the results.
			Assert.assertNotNull(results);
			Assert.assertEquals(SynpufArchive.SAMPLE_1.getBeneficiaryCount() * 3,
					results.stream().mapToInt(r -> r.getResourcesPushedCount()).sum());

			// TODO verify results by actually querying the server.
		}
	}
}