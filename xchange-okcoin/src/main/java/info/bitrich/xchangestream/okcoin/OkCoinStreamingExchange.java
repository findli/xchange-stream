package info.bitrich.xchangestream.okcoin;

import info.bitrich.xchangestream.core.StreamingExchange;
import info.bitrich.xchangestream.core.StreamingMarketDataService;
import io.reactivex.Completable;
import org.knowm.xchange.okcoin.OkCoinExchange;

public class OkCoinStreamingExchange extends OkCoinExchange implements StreamingExchange {
    private static final String API_URI = "wss://real.okcoin.com:10440/websocket/okcoinapi";

    private final OkCoinStreamingService streamingService;
    private OkCoinStreamingMarketDataService streamingMarketDataService;

    public OkCoinStreamingExchange() {
        streamingService = new OkCoinStreamingService(API_URI);
    }

    @Override
    protected void initServices() {
        super.initServices();
        streamingMarketDataService = new OkCoinStreamingMarketDataService(streamingService);
    }

    @Override
    public Completable connect() {
        return streamingService.connect();
    }

    public Completable onClientDisconnect() {
        return streamingService.onClientDisconnect();
    }

    @Override
    public Completable disconnect() {
        return streamingService.disconnect();
    }

    @Override
    public StreamingMarketDataService getStreamingMarketDataService() {
        return streamingMarketDataService;
    }
}
