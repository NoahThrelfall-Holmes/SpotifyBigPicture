package de.selbi.spotify.playback.data.visual;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.google.common.collect.Iterables;
import com.neovisionaries.i18n.CountryCode;

import de.selbi.spotify.bot.api.BotException;
import de.selbi.spotify.bot.api.SpotifyCall;
import de.selbi.spotify.bot.config.Config;
import de.selbi.spotify.bot.util.BotUtils;
import de.selbi.spotify.playback.data.PlaybackInfoDTO;
import de.selbi.spotify.playback.data.help.ListTrackDTO;
import de.selbi.spotify.playback.data.help.PlaybackInfoConstants;
import se.michaelthelin.spotify.SpotifyApi;
import se.michaelthelin.spotify.enums.CurrentlyPlayingType;
import se.michaelthelin.spotify.enums.ModelObjectType;
import se.michaelthelin.spotify.model_objects.miscellaneous.CurrentlyPlayingContext;
import se.michaelthelin.spotify.model_objects.specification.Album;
import se.michaelthelin.spotify.model_objects.specification.Artist;
import se.michaelthelin.spotify.model_objects.specification.Context;
import se.michaelthelin.spotify.model_objects.specification.Playlist;
import se.michaelthelin.spotify.model_objects.specification.PlaylistTrack;
import se.michaelthelin.spotify.model_objects.specification.Show;
import se.michaelthelin.spotify.model_objects.specification.Track;
import se.michaelthelin.spotify.model_objects.specification.TrackSimplified;
import se.michaelthelin.spotify.requests.data.artists.GetArtistsTopTracksRequest;

@Component
public class ContextProvider {

  private static final int MAX_IMMEDIATE_TRACKS = 50;

  private final SpotifyApi spotifyApi;

  @Autowired
  private Config config;

  private String previousContextString;
  private Album currentContextAlbum;
  private List<TrackSimplified> currentContextAlbumTracks;
  private List<ListTrackDTO> formattedAlbumTracks;
  private List<ListTrackDTO> formattedPlaylistTracks;
  private Integer currentlyPlayingAlbumTrackNumber;

  ContextProvider(SpotifyApi spotifyApi) {
    this.spotifyApi = spotifyApi;
    this.formattedAlbumTracks = new ArrayList<>();
    this.formattedPlaylistTracks = new ArrayList<>();
  }

  /**
   * Get the name of the currently playing context (either a playlist name, an
   * artist, or an album). Only works on Tracks.
   *
   * @param info     the context info
   * @param previous the previous info to compare to
   * @return a String of the current context, null if none was found
   */
  public String findContextName(CurrentlyPlayingContext info, PlaybackInfoDTO previous) {
    String contextName = null;
    try {
      Context context = info.getContext();
      boolean force = previous == null || previous.getContext() == null || previous.getContext().isEmpty();
      if (context != null) {
        ModelObjectType type = context.getType();
        if (ModelObjectType.PLAYLIST.equals(type)) {
          contextName = getPlaylistContext(context, force);
        } else if (ModelObjectType.ARTIST.equals(type)) {
          contextName = getArtistContext(context, force);
        } else if (ModelObjectType.ALBUM.equals(type)) {
          contextName = getAlbumContext(info, force);
        } else if (ModelObjectType.SHOW.equals(type)) {
          contextName = getPodcastContext(info, force);
        }
      }
    } catch (BotException e) {
      e.printStackTrace();
    }
    if (contextName != null) {
      return contextName;
    } else {
      return previous != null && previous.getContext() != null
          ? previous.getContext()
          : info.getCurrentlyPlayingType().toString();
    }
  }

  public List<ListTrackDTO> getFormattedAlbumTracks() {
    return formattedAlbumTracks;
  }

  public List<ListTrackDTO> getFormattedPlaylistTracks() {
    return formattedPlaylistTracks;
  }

  public Integer getCurrentlyPlayingAlbumTrackNumber() {
    return currentlyPlayingAlbumTrackNumber;
  }

  public Integer getCurrentlyPlayingPlaylistTrackNumber(CurrentlyPlayingContext context) {
    String id = context.getItem().getId();
    return Iterables.indexOf(formattedPlaylistTracks, t -> {
      if (t != null) {
        if (t.getId() != null) {
          return t.getId().equals(id);
        } else {
          Track item = (Track) context.getItem();
          return BotUtils.getFirstArtistName(item).equals(t.getArtist()) && item.getName().equals(t.getTitle());
        }
      }
      return false;
    }) + 1;
  }

  private String getArtistContext(Context context, boolean force) {
    if (force || didContextChange(context)) {
      String artistId = context.getHref().replace(PlaybackInfoConstants.ARTIST_PREFIX, "");
      Artist contextArtist = SpotifyCall.execute(spotifyApi.getArtist(artistId));
      Track[] artistTopTracks = SpotifyCall.execute(spotifyApi.getArtistsTopTracks(artistId, config.spotifyBotConfig().getMarket()));

      List<ListTrackDTO> listTrackDTOS = new ArrayList<>();
      for (int i = 0; i < artistTopTracks.length; i++) {
        Track track = artistTopTracks[i];
        ListTrackDTO lt = new ListTrackDTO(track.getId(), i + 1, BotUtils.getFirstArtistName(track), track.getName(), track.getDurationMs());
        listTrackDTOS.add(lt);
      }
      this.formattedPlaylistTracks = listTrackDTOS;

      long sum = this.formattedPlaylistTracks.stream()
          .mapToLong(ListTrackDTO::getLength)
          .sum();
      String formattedTime = formatTime((int) sum);

      return "ARTIST: " + contextArtist.getName() + " //// " + this.formattedPlaylistTracks.size() + " tracks // " + formattedTime;
    }
    return null;
  }

  private String getPlaylistContext(Context context, boolean force) {
    if (force || didContextChange(context)) {
      String playlistId = context.getHref().replace(PlaybackInfoConstants.PLAYLIST_PREFIX, "");
      Playlist contextPlaylist = SpotifyCall.execute(spotifyApi.getPlaylist(playlistId));

      List<PlaylistTrack> playlistTracks = SpotifyCall.executePaging(spotifyApi.getPlaylistsItems(playlistId));
      List<ListTrackDTO> listTrackDTOS = new ArrayList<>();
      for (int i = 0; i < playlistTracks.size(); i++) {
        Track track = (Track) playlistTracks.get(i).getTrack();
        ListTrackDTO lt = new ListTrackDTO(track.getId(), i + 1, BotUtils.getFirstArtistName(track), track.getName(), track.getDurationMs());
        listTrackDTOS.add(lt);
      }
      this.formattedPlaylistTracks = listTrackDTOS;

      long sum = this.formattedPlaylistTracks.stream()
      	.mapToLong(ListTrackDTO::getLength)
      	.sum();      
      String formattedTime = formatTime((int) sum);
      
      return contextPlaylist.getName() + " //// " + this.formattedPlaylistTracks.size() + " tracks // " + formattedTime;
    }
    return null;
  }

  private String getAlbumContext(CurrentlyPlayingContext info, boolean force) {
    Context context = info.getContext();
    Track track = null;
    String albumId;
    if (info.getCurrentlyPlayingType().equals(CurrentlyPlayingType.TRACK)) {
      track = (Track) info.getItem();
      albumId = track.getAlbum().getId();
    } else {
      albumId = BotUtils.getIdFromUri(context.getUri());
    }

    if (force || didContextChange(context)) {
      currentContextAlbum = SpotifyCall.execute(spotifyApi.getAlbum(albumId));
      if (currentContextAlbum.getTracks().getTotal() > MAX_IMMEDIATE_TRACKS) {
        currentContextAlbumTracks = SpotifyCall.executePaging(spotifyApi.getAlbumsTracks(albumId));
      } else {
        currentContextAlbumTracks = Arrays.asList(currentContextAlbum.getTracks().getItems());
      }

      formattedAlbumTracks = new ArrayList<>();
      for (int i = 0; i < currentContextAlbumTracks.size(); i++) {
        TrackSimplified ts = currentContextAlbumTracks.get(i);
        formattedAlbumTracks.add(new ListTrackDTO(track.getId(), i + 1, BotUtils.getFirstArtistName(ts), ts.getName(), ts.getDurationMs()));
      }
    }
    if (currentContextAlbumTracks != null && track != null) {
      // Track number (unfortunately, can't simply use track numbers because of disc numbers)
      final String trackId = track.getId();
      currentlyPlayingAlbumTrackNumber = Iterables.indexOf(currentContextAlbumTracks, t -> Objects.requireNonNull(t).getId().equals(trackId)) + 1;

      // Total album duration
      Integer totalDurationMs = currentContextAlbumTracks.stream().mapToInt(TrackSimplified::getDurationMs).sum();
      String totalDurationFormatted = formatTime(totalDurationMs);

      // Assemble it all
      if (currentlyPlayingAlbumTrackNumber > 0) {
        Integer totalTrackCount = currentContextAlbum.getTracks().getTotal();
        int digits = totalTrackCount.toString().length();
        return String.format("Total Time: %s // Track: %0" + digits + "d of %0" + digits + "d",
            totalDurationFormatted,
            currentlyPlayingAlbumTrackNumber,
            totalTrackCount);
      }
    }

    // Fallback when playing back from the queue
    return "Queue >> ALBUM: " + currentContextAlbum.getArtists()[0].getName() + " - " + currentContextAlbum.getName();
  }

  private String getPodcastContext(CurrentlyPlayingContext info, boolean force) {
    Context context = info.getContext();
    String showId = BotUtils.getIdFromUri(context.getUri());
    if (force || didContextChange(context)) {
      Show show = SpotifyCall.execute(spotifyApi.getShow(showId));
      return "PODCAST: " + show.getName();
    }
    return null;
  }

  private boolean didContextChange(Context context) {
    if (!context.toString().equals(previousContextString)) {
      this.previousContextString = context.toString();
      return true;
    }
    return false;
  }

  private String formatTime(Integer timeInMs) {
    Duration duration = Duration.ofMillis(timeInMs);
    long hours = duration.toHours();
    int minutesPart = duration.toMinutesPart();
    if (hours > 0) {
      return String.format("%d hr %d min", hours, minutesPart);
    } else {
      int secondsPart = duration.toSecondsPart();
      return String.format("%d min %d sec", minutesPart, secondsPart);
    }
  }
}