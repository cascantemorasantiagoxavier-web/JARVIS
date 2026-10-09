package com.example.jarvis.data.remote

interface RemoteDataSource {
    suspend fun fetchRemoteData(): String
}
