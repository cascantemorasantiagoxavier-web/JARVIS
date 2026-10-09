package com.example.jarvis.data.remote

class RemoteDataSourceImpl : RemoteDataSource {
    override suspend fun fetchRemoteData(): String {
        return "Remote data sync successful"
    }
}
