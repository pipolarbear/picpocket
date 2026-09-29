package com.picpocket.app.di

import android.content.Context
import androidx.room.Room
import com.picpocket.app.data.local.PicPocketDatabase
import com.picpocket.app.data.local.dao.TagDao
import com.picpocket.app.data.repository.DocumentRepository
import com.picpocket.app.data.repository.DocumentRepositoryImpl
import com.picpocket.app.domain.filter.BinarizeFilter
import com.picpocket.app.domain.filter.BrightnessFilter
import com.picpocket.app.domain.filter.ContrastFilter
import com.picpocket.app.domain.filter.FilterPipeline
import com.picpocket.app.domain.filter.GrayscaleFilter
import com.picpocket.app.domain.filter.SharpenFilter
import com.picpocket.app.domain.ocr.MlKitOcrEngine
import com.picpocket.app.domain.ocr.OcrEngine
import com.picpocket.app.domain.export.ImageOnlyPdfGenerator
import com.picpocket.app.domain.export.PdfGenerator
import com.picpocket.app.domain.export.SearchablePdfGenerator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ImageOnlyPdf

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SearchablePdf

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): PicPocketDatabase {
        return Room.databaseBuilder(
            context,
            PicPocketDatabase::class.java,
            "picpocket.db",
        ).fallbackToDestructiveMigration().build()
    }

    @Provides
    fun provideTagDao(database: PicPocketDatabase): TagDao = database.tagDao()

    @Provides
    @Singleton
    fun provideDocumentRepository(impl: DocumentRepositoryImpl): DocumentRepository = impl

    @Provides
    @Singleton
    fun provideOcrEngine(): OcrEngine = MlKitOcrEngine()

    @Provides
    @Singleton
    fun provideFilterPipeline(
        grayscaleFilter: GrayscaleFilter,
        contrastFilter: ContrastFilter,
        brightnessFilter: BrightnessFilter,
        sharpenFilter: SharpenFilter,
        binarizeFilter: BinarizeFilter,
    ): FilterPipeline = FilterPipeline(
        grayscaleFilter, contrastFilter, brightnessFilter, sharpenFilter, binarizeFilter,
    )

    @Provides
    @Singleton
    @ImageOnlyPdf
    fun provideImageOnlyPdfGenerator(): PdfGenerator = ImageOnlyPdfGenerator()

    @Provides
    @Singleton
    @SearchablePdf
    fun provideSearchablePdfGenerator(): PdfGenerator = SearchablePdfGenerator()
}
