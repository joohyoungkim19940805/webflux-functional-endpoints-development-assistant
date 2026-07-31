package com.byeolnaerim.watch.document;


import java.util.Objects;
import com.byeolnaerim.watch.AbstractWatcher;


/**
 * Base watcher for generators that consume a shared, read-only Spoon model.
 */
public abstract class AbstractSpoonDocumentWatcher extends AbstractWatcher {

	private SpoonAnalysisCache spoonAnalysisCache = new SpoonAnalysisCache();

	private boolean sharedSpoonAnalysisCache;

	/**
	 * Injects the orchestrator-level analysis cache.
	 *
	 * @param spoonAnalysisCache
	 *            shared analysis cache
	 */
	public final void useSpoonAnalysisCache(
		SpoonAnalysisCache spoonAnalysisCache
	) {

		this.spoonAnalysisCache = Objects.requireNonNull( spoonAnalysisCache );
		this.sharedSpoonAnalysisCache = true;

	}

	/**
	 * Builds or reuses the analysis context for one document generation pass.
	 */
	protected final SpoonAnalysisContext analyzeSpoon(
		SpoonAnalysisRequest request
	) {

		if (! sharedSpoonAnalysisCache) {
			spoonAnalysisCache.beginGeneration();

		}

		return spoonAnalysisCache.analyze( request );

	}

}
