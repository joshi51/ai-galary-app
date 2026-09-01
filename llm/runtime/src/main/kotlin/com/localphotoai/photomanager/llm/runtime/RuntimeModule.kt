package com.localphotoai.photomanager.llm.runtime

import com.localphotoai.photomanager.core.common.Logger
import com.localphotoai.photomanager.domain.organization.BuildOrganizationPlanUseCase
import com.localphotoai.photomanager.domain.person.PersonRepository
import com.localphotoai.photomanager.domain.photo.GetPhotoMetadataUseCase
import com.localphotoai.photomanager.domain.photo.PhotoRepository
import com.localphotoai.photomanager.domain.search.SearchPhotosUseCase
import com.localphotoai.photomanager.domain.similarity.PhotoGroupRepository
import com.localphotoai.photomanager.domain.statistics.GetStorageStatisticsUseCase
import com.localphotoai.photomanager.domain.tool.LlmEngine
import com.localphotoai.photomanager.domain.tool.LlmModelDownloader
import com.localphotoai.photomanager.llm.orchestration.LogcatTraceLogger
import com.localphotoai.photomanager.llm.orchestration.ToolCallLoop
import com.localphotoai.photomanager.llm.orchestration.TraceLogger
import com.localphotoai.photomanager.tools.FindDuplicatesTool
import com.localphotoai.photomanager.tools.FindSimilarPhotosTool
import com.localphotoai.photomanager.tools.GetPhotoMetadataTool
import com.localphotoai.photomanager.tools.GetStorageStatisticsTool
import com.localphotoai.photomanager.tools.BuildOrganizationPlanTool
import com.localphotoai.photomanager.tools.SearchPhotosTool
import com.localphotoai.photomanager.tools.ToolRegistry
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Every `:tools`/`:llm:orchestration` class uses a plain constructor (no `@Inject` — those are
 * plain-Kotlin modules with no Hilt plugin applied), so this Android+Hilt module wires all of
 * them explicitly, the same way `DatabaseModule`'s companion object wires `:domain` use cases.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RuntimeModule {

    @Binds
    @Singleton
    abstract fun bindLlmEngine(impl: LlamaCppEngine): LlmEngine

    @Binds
    @Singleton
    abstract fun bindLlmModelDownloader(impl: HttpLlmModelDownloader): LlmModelDownloader

    @Binds
    @Singleton
    abstract fun bindTraceLogger(impl: LogcatTraceLogger): TraceLogger

    companion object {
        @Provides
        fun provideLogcatTraceLogger(logger: Logger): LogcatTraceLogger = LogcatTraceLogger(logger)

        @Provides
        fun provideSearchPhotosTool(
            searchPhotosUseCase: SearchPhotosUseCase,
            personRepository: PersonRepository,
        ): SearchPhotosTool = SearchPhotosTool(searchPhotosUseCase, personRepository)

        @Provides
        fun provideFindDuplicatesTool(
            photoGroupRepository: PhotoGroupRepository,
            photoRepository: PhotoRepository,
        ): FindDuplicatesTool = FindDuplicatesTool(photoGroupRepository, photoRepository)

        @Provides
        fun provideFindSimilarPhotosTool(
            photoGroupRepository: PhotoGroupRepository,
            photoRepository: PhotoRepository,
        ): FindSimilarPhotosTool = FindSimilarPhotosTool(photoGroupRepository, photoRepository)

        @Provides
        fun provideGetPhotoMetadataTool(useCase: GetPhotoMetadataUseCase): GetPhotoMetadataTool =
            GetPhotoMetadataTool(useCase)

        @Provides
        fun provideGetStorageStatisticsTool(useCase: GetStorageStatisticsUseCase): GetStorageStatisticsTool =
            GetStorageStatisticsTool(useCase)

        @Provides
        fun provideBuildOrganizationPlanTool(useCase: BuildOrganizationPlanUseCase): BuildOrganizationPlanTool =
            BuildOrganizationPlanTool(useCase)

        @Provides
        @Singleton
        fun provideToolRegistry(
            searchPhotosTool: SearchPhotosTool,
            findDuplicatesTool: FindDuplicatesTool,
            findSimilarPhotosTool: FindSimilarPhotosTool,
            getPhotoMetadataTool: GetPhotoMetadataTool,
            getStorageStatisticsTool: GetStorageStatisticsTool,
            buildOrganizationPlanTool: BuildOrganizationPlanTool,
        ): ToolRegistry = ToolRegistry(
            listOf(
                searchPhotosTool, findDuplicatesTool, findSimilarPhotosTool,
                getPhotoMetadataTool, getStorageStatisticsTool, buildOrganizationPlanTool,
            ),
        )

        @Provides
        fun provideToolCallLoop(
            engine: LlmEngine,
            toolRegistry: ToolRegistry,
            traceLogger: TraceLogger,
        ): ToolCallLoop = ToolCallLoop(engine, toolRegistry, traceLogger)
    }
}
