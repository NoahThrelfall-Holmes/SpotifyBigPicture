package spotify.playback.data.visual;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.google.common.collect.Iterables;
import com.neovisionaries.i18n.CountryCode;

import se.michaelthelin.spotify.SpotifyApi;
import se.michaelthelin.spotify.enums.AlbumType;
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
import spotify.api.BotException;
import spotify.api.SpotifyCall;
import spotify.playback.data.PlaybackInfoDTO;
import spotify.playback.data.help.ListTrackDTO;
import spotify.playback.data.help.PlaybackInfoConstants;
import spotify.services.UserService;
import spotify.util.BotLogger;
import spotify.util.BotUtils;
import spotify.util.data.AlbumTrackPair;

@Component
public class ContextProvider {
  public static final String QUEUE_PREFIX = "Queue >> ";

  private static final int MAX_IMMEDIATE_TRACKS = 50;

  private final SpotifyApi spotifyApi;
  private final UserService userService;
  private final BotLogger log;

  private String previousContextString;
  private Album currentContextAlbum;
  private List<TrackSimplified> currentContextAlbumTracks;
  private List<ListTrackDTO> formattedAlbumTracks;
  private List<ListTrackDTO> formattedPlaylistTracks;
  private Integer currentlyPlayingAlbumTrackNumber;
  private List<ListTrackDTO> formattedQueue;
  private CountryCode market;

  private boolean queueEnabled;

  ContextProvider(SpotifyApi spotifyApi, UserService userService, BotLogger botLogger) {
    this.spotifyApi = spotifyApi;
    this.userService = userService;
    this.log = botLogger;
    this.formattedAlbumTracks = new ArrayList<>();
    this.formattedPlaylistTracks = new ArrayList<>();
    this.formattedQueue = new ArrayList<>();
    this.queueEnabled = true;
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

  public Integer getTotalDiscCount() {
    return currentContextAlbumTracks.stream().mapToInt(TrackSimplified::getDiscNumber).max().orElse(1);
  }

  public Integer getCurrentlyPlayingPlaylistTrackNumber(CurrentlyPlayingContext context) {
    String id = context.getItem().getId();
    return Iterables.indexOf(formattedPlaylistTracks, t -> {
      if (t != null) {
        if (t.getId() != null) {
          return t.getId().equals(id);
        } else {
          Track item = (Track) context.getItem();
          return t.getArtists().containsAll(BotUtils.toArtistNamesList(item)) && item.getName().equals(t.getTitle());
        }
      }
      return false;
    }) + 1;
  }

  public List<ListTrackDTO> getQueue() {
    return this.formattedQueue;
  }

  public void refreshQueue() throws BotException {
    if (queueEnabled) {
      try {
        List<Track> rawQueue = SpotifyCall.execute(spotifyApi.getTheUsersQueue()).getQueue();
        if (rawQueue != null && !rawQueue.isEmpty()) {
          this.formattedQueue = rawQueue.stream()
              .map(ListTrackDTO::fromTrack)
              .collect(Collectors.toList());
        }
      } catch (BotException e) {
        this.formattedQueue = List.of();
        queueEnabled = false;
        log.error("Queue has been disabled. This feature is only available to Spotify premium users");
      }
    }
  }

  private String getArtistContext(Context context, boolean force) {
    if (force || didContextChange(context)) {
      String artistId = context.getHref().replace(PlaybackInfoConstants.ARTIST_PREFIX, "");
      Artist contextArtist = SpotifyCall.execute(spotifyApi.getArtist(artistId));
      Track[] artistTopTracks = SpotifyCall.execute(spotifyApi.getArtistsTopTracks(artistId, getMarketOfCurrentUser()));

      List<ListTrackDTO> listTrackDTOS = new ArrayList<>();
      for (Track track : artistTopTracks) {
        ListTrackDTO lt = ListTrackDTO.fromTrack(track);
        listTrackDTOS.add(lt);
      }
      this.formattedPlaylistTracks = listTrackDTOS;

      return "ARTIST TOP TRACKS: " + contextArtist.getName();
    }
    return null;
  }

  private String getPlaylistContext(Context context, boolean force) {
    if (force || didContextChange(context)) {
      String playlistId = context.getHref().replace(PlaybackInfoConstants.PLAYLIST_PREFIX, "");
      Playlist contextPlaylist = SpotifyCall.execute(spotifyApi.getPlaylist(playlistId));

      List<PlaylistTrack> playlistTracks = new ArrayList<>(Arrays.asList(contextPlaylist.getTracks().getItems()));
      playlistTracks.addAll(SpotifyCall.executePaging(spotifyApi.getPlaylistsItems(playlistId).offset(100)));
      List<ListTrackDTO> listTrackDTOS = new ArrayList<>();
      for (PlaylistTrack playlistTrack : playlistTracks) {
        Track track = (Track) playlistTrack.getTrack();
        ListTrackDTO lt = ListTrackDTO.fromTrack(track);
        listTrackDTOS.add(lt);
      }
      this.formattedPlaylistTracks = listTrackDTOS;
      
      return contextPlaylist.getName();
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
      if (track != null) {
        for (TrackSimplified ts : currentContextAlbumTracks) {
          ListTrackDTO e = ListTrackDTO.fromTrack(ts);
          formattedAlbumTracks.add(e);
        }
      }
    }
    String contextString = getReleaseTypeString() + ": " + currentContextAlbum.getArtists()[0].getName() + " \u2013 " + currentContextAlbum.getName();
    if (currentContextAlbumTracks != null && track != null) {
      // Track number (unfortunately, can't simply use track numbers because of disc numbers)
      final String trackId = track.getId();
      this.currentlyPlayingAlbumTrackNumber = Iterables.indexOf(currentContextAlbumTracks, t -> Objects.requireNonNull(t).getId().equals(trackId)) + 1;
      if (this.currentlyPlayingAlbumTrackNumber > 0) {
        return contextString;
      }
    }

    // Fallback when playing back from the queue
    return QUEUE_PREFIX + contextString;
  }

  private String getReleaseTypeString() {
    if (currentContextAlbum.getAlbumType() == AlbumType.SINGLE) {
      AlbumTrackPair atp = AlbumTrackPair.of(BotUtils.asAlbumSimplified(currentContextAlbum), currentContextAlbumTracks);
      if (BotUtils.isExtendedPlay(atp)) {
        return "EP";
      }
    }
    return currentContextAlbum.getAlbumType().toString();
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

  private CountryCode getMarketOfCurrentUser() {
    if (this.market == null) {
      this.market = userService.getMarketOfCurrentUser();
      if (this.market == null) {
        throw new IllegalStateException("Market is null (user-read-private scope missing?)");
      }
    }
    return this.market;
  }
}
