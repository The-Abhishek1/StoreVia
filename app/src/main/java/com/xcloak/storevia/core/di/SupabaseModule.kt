package com.xcloak.storevia.core.di

import com.xcloak.storevia.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import javax.inject.Singleton

/**
 * Supabase is used for auth, backup and (later) sync. Room remains the source of truth on-device,
 * so the app works fully offline. Only the public ANON key ships in the app; data safety comes from
 * Row Level Security policies on the Postgres tables (see supabase/schema.sql).
 */
@Module
@InstallIn(SingletonComponent::class)
object SupabaseModule {
    @Provides @Singleton
    fun supabase(): SupabaseClient = createSupabaseClient(
        supabaseUrl = BuildConfig.SUPABASE_URL.ifBlank { "https://example.supabase.co" },
        supabaseKey = BuildConfig.SUPABASE_ANON_KEY.ifBlank { "anon-key-not-set" },
    ) {
        install(Auth)
        install(Postgrest)
        install(Storage)
    }
}
