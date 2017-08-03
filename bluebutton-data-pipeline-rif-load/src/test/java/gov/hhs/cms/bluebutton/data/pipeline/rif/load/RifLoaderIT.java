package gov.hhs.cms.bluebutton.data.pipeline.rif.load;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Slf4jReporter;

import gov.hhs.cms.bluebutton.data.model.rif.RifFileEvent;
import gov.hhs.cms.bluebutton.data.model.rif.RifFileRecords;
import gov.hhs.cms.bluebutton.data.model.rif.RifFilesEvent;
import gov.hhs.cms.bluebutton.data.model.rif.samples.StaticRifResource;
import gov.hhs.cms.bluebutton.data.model.rif.samples.StaticRifResourceGroup;
import gov.hhs.cms.bluebutton.data.pipeline.rif.load.RifRecordLoadResult.LoadAction;
import gov.hhs.cms.bluebutton.datapipeline.rif.extract.RifFilesProcessor;

/**
 * Integration tests for {@link RifLoader}.
 */
public final class RifLoaderIT {
	private static final Logger LOGGER = LoggerFactory.getLogger(RifLoaderIT.class);

	/**
	 * Runs {@link RifLoader} against the
	 * {@link StaticRifResourceGroup#SAMPLE_A} data.
	 */
	@Test
	public void loadSampleA() {
		loadSample(StaticRifResourceGroup.SAMPLE_A);
	}

	/**
	 * Runs {@link RifLoader} against the
	 * {@link StaticRifResourceGroup#SAMPLE_B} data.
	 */
	@Test
	public void loadSampleB() {
		loadSample(StaticRifResourceGroup.SAMPLE_B);
	}

	/**
	 * Runs {@link RifLoader} against the specified
	 * {@link StaticRifResourceGroup}.
	 * 
	 * @param sampleGroup
	 *            the {@link StaticRifResourceGroup} to load
	 */
	private void loadSample(StaticRifResourceGroup sampleGroup) {
		// Generate the sample RIF data to feed through the pipeline.
		List<StaticRifResource> sampleResources = Arrays.stream(sampleGroup.getResources())
				.collect(Collectors.toList());
		RifFilesEvent rifFilesEvent = new RifFilesEvent(Instant.now(),
				sampleResources.stream().map(r -> r.toRifFile()).collect(Collectors.toList()));

		// Create the processors that will handle each stage of the pipeline.
		MetricRegistry appMetrics = new MetricRegistry();
		RifFilesProcessor processor = new RifFilesProcessor();
		LoadAppOptions options = RifLoaderTestUtils.getLoadOptions();
		RifLoader loader = new RifLoader(appMetrics, options);

		// Link up the pipeline and run it.
		LOGGER.info("Loading RIF records...");
		AtomicInteger failureCount = new AtomicInteger(0);
		Queue<RifRecordLoadResult> successfulRecords = new ConcurrentLinkedQueue<>();
		for (RifFileEvent rifFileEvent : rifFilesEvent.getFileEvents()) {
			RifFileRecords rifFileRecords = processor.produceRecords(rifFileEvent);
			loader.process(rifFileRecords, error -> {
				failureCount.incrementAndGet();
				LOGGER.warn("Record(s) failed to load.", error);
			}, result -> {
				successfulRecords.add(result);
			});
			Slf4jReporter.forRegistry(rifFileEvent.getEventMetrics()).outputTo(LOGGER).build().report();
		}
		LOGGER.info("Loaded RIF records: '{}'.", successfulRecords.size());
		Slf4jReporter.forRegistry(appMetrics).outputTo(LOGGER).build().report();

		// Verify that the expected number of records were run successfully.
		Assert.assertEquals(0, failureCount.get());
		// FIXME remove successfulRecords filter once CBBD-266 is resolved
		Assert.assertEquals("Unexpected number of successful records: " + successfulRecords,
				sampleResources.stream().mapToInt(r -> r.getRecordCount()).sum(),
				successfulRecords.stream().filter(r -> r.getLoadAction() == LoadAction.INSERTED).count());

		/*
		 * Run the extraction an extra time and verify that each record can now
		 * be found in the database.
		 */
		EntityManagerFactory entityManagerFactory = RifLoaderTestUtils.createEntityManagerFactory(options);
		for (RifFileEvent rifFileEvent : rifFilesEvent.getFileEvents()) {
			RifFileRecords rifFileRecordsCopy = processor.produceRecords(rifFileEvent);
			assertAreInDatabase(entityManagerFactory, rifFileRecordsCopy.getRecords().map(r -> r.getRecord()));
		}
	}

	/**
	 * Ensures that {@link RifLoaderTestUtils#cleanDatabaseServer()} is called
	 * after each test case.
	 */
	@After
	public void cleanDatabaseServerAfterEachTestCase() {
		RifLoaderTestUtils.cleanDatabaseServer(RifLoaderTestUtils.getLoadOptions());
	}

	/**
	 * Verifies that the specified RIF records are actually in the database.
	 * 
	 * @param entityManagerFactory
	 *            the {@link EntityManagerFactory} to use
	 * @param records
	 *            the RIF records to verify
	 */
	private static void assertAreInDatabase(EntityManagerFactory entityManagerFactory, Stream<Object> records) {
		EntityManager entityManager = null;
		try {
			entityManager = entityManagerFactory.createEntityManager();

			for (Object record : records.collect(Collectors.toList())) {
				Object recordId = entityManagerFactory.getPersistenceUnitUtil().getIdentifier(record);
				Object recordFromDb = entityManager.find(record.getClass(), recordId);
				Assert.assertNotNull(recordFromDb);
			}
		} finally {
			if (entityManager != null)
				entityManager.close();
		}
	}
}