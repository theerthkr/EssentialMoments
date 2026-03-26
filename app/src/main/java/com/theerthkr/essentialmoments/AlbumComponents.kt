package com.theerthkr.essentialmoments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

@Composable
fun AlbumCard(album: Album, onClick: (Album) -> Unit){

    Column(modifier = Modifier
        .fillMaxWidth()
        .padding(4.dp) //space around the card
        .clip(shape = RoundedCornerShape(12.dp))
        .clickable { onClick(album) }
    ){
        Box(
            modifier = Modifier
                .aspectRatio(1f) // Makes it a square
                .clip(RoundedCornerShape(12.dp)) // Rounds the corners
                .background(Color.DarkGray) // Placeholder for the actual image
        ){
            AsyncImage(
                model = album.coverUri, // This is the file path from MediaStore
                contentDescription = "Cover for ${album.name}",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp) // Or whatever height you want
                    .clip(RoundedCornerShape(12.dp)), // Nice rounded corners
                contentScale = ContentScale.Crop // Fills the square nicely
            )
        }
        Text(
            modifier = Modifier.padding(top = 4.dp, start = 2.dp),
            text = album.name,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1 // Keeps it neat if the name is too long

        )
        Text(
            text = "${album.photoCount} photos",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )
    }
}

@Composable
fun AlbumGrid(
    albums: List<Album>,
    onAlbumClick: (Album) -> Unit
) {

    LazyVerticalGrid(
        columns = GridCells.Fixed(3), // MAGIC NUMBER: Forces 3 columns
        contentPadding = PaddingValues(top = 64.dp, start = 8.dp, end = 8.dp, bottom = 8.dp)
    ) {
        items(albums) { album ->
            AlbumCard(
                album = album,
                onClick = { onAlbumClick(album) }
            )
        }


    }
}