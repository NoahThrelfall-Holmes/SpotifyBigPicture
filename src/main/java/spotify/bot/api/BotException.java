package spotify.bot.api;

import com.wrapper.spotify.exceptions.SpotifyWebApiException;

/**
 * A general wrapper for every type of Exception related to outside requests to
 * the Spotify Web API, most commonly (but not limited to) the
 * {@link SpotifyWebApiException}.
 */
public class BotException extends RuntimeException {
	private static final long serialVersionUID = 1108719662083800510L;
	
	private final Exception baseException;

    public BotException(Exception e) {
        this.baseException = e;
    }

    @Override
    public String toString() {
        return baseException.toString();
    }
}
