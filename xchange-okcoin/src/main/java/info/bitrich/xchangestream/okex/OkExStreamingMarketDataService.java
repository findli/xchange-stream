package info.bitrich.xchangestream.okex;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import info.bitrich.xchangestream.core.StreamingMarketDataService;
import info.bitrich.xchangestream.okcoin.OkCoinStreamingService;
import info.bitrich.xchangestream.okex.dto.FutureIndex;
import info.bitrich.xchangestream.okex.dto.TickerJson;
import io.reactivex.Observable;
import java.math.BigDecimal;
import java.util.Date;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.marketdata.OrderBook;
import org.knowm.xchange.dto.marketdata.Ticker;
import org.knowm.xchange.dto.marketdata.Trade;
import org.knowm.xchange.exceptions.NotYetImplementedForExchangeException;
import org.knowm.xchange.okcoin.FuturesContract;
import org.knowm.xchange.okcoin.dto.marketdata.OkCoinDepth;

public class OkExStreamingMarketDataService implements StreamingMarketDataService {
    private final OkCoinStreamingService service;

    OkExStreamingMarketDataService(OkCoinStreamingService service) {
        this.service = service;
    }

    public enum Tool {
        BTC("btc"), LTC("ltc"), ETH("eth"), ETC("etc"), BCH("bch");
        String name;
        Tool(String name) {
            this.name = name;
        }
        public String getName() {
            return name;
        }
    }

    public enum Depth {
        DEPTH_20("20"), DEPTH_60("60");
        String name;
        Depth(String name) {
            this.name = name;
        }
        public String getName() {
            return name;
        }
    }

    @Override
    public Observable<OrderBook> getOrderBook(CurrencyPair currencyPair, Object... args) {
        // ok_sub_future_%s_depth_%s_%s - Market Depth(Full)
        // 1 value of X is: btc, ltc
        // 2 value of Y is: this_week, next_week, quarter
        // 3 value of Z is: 20, 60
        if (args.length != 3) {
            throw new IllegalArgumentException("Missing required params:\n" +
                    "1 value of X is: btc, ltc, eth\n" +
                    "2 value of Y is: this_week, next_week, quarter\n" +
                    "3 value of Z is: 20, 60");
        }
        final FuturesContract futuresContract = FuturesContract.valueOf(String.valueOf(args[1]));
        final Depth depth = Depth.valueOf(String.valueOf(args[2]));

        return getOrderBook(currencyPair, futuresContract, depth);
    }

    public Observable<OrderBook> getOrderBook(CurrencyPair currencyPair, FuturesContract futuresContract, Depth depth) {
        final Tool tool = Tool.valueOf(currencyPair.base.getCurrencyCode());
        String channel = String.format("ok_sub_futureusd_%s_depth_%s_%s", tool.getName(), futuresContract.getName(), depth.getName());
        return service.subscribeChannel(channel)
                .map(s -> {
                    ObjectMapper mapper = new ObjectMapper();
                    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                    OkCoinDepth okCoinDepth = mapper.treeToValue(s.get("data"), OkCoinDepth.class);
                    return OkExAdapters.adaptOrderBook(okCoinDepth, currencyPair);
                });
    }

    @Override
    public Observable<Ticker> getTicker(CurrencyPair currencyPair, Object... args) {
        final String futuresContractName = ((FuturesContract) args[0]).getName();
        final Tool tool = Tool.valueOf(currencyPair.base.getCurrencyCode());
        final String channel = String.format("ok_sub_futureusd_%s_ticker_%s", tool.getName(), futuresContractName);
        return service.subscribeChannel(channel)
                .map(s -> {
                    ObjectMapper mapper = new ObjectMapper();
                    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                    mapper.registerModule(new JavaTimeModule());

                    final TickerJson tickerJson = mapper.treeToValue(s.get("data"), TickerJson.class);

                    return new Ticker.Builder()
                            .currencyPair(currencyPair)
                            .last(tickerJson.getLast())
                            .bid(tickerJson.getBuy())
                            .ask(tickerJson.getSell())
                            .high(new BigDecimal(tickerJson.getLimitHigh()))
                            .low(new BigDecimal(tickerJson.getLimitLow()))
                            .volume(tickerJson.getVol())
                            .timestamp(new Date())
                            .build();
                });
    }

    @Override
    public Observable<Trade> getTrades(CurrencyPair currencyPair, Object... args) {
        throw new NotYetImplementedForExchangeException("Use StreamingPrivateMarketDataService instead");
    }

    public Observable<FutureIndex> getFutureIndex(CurrencyPair currencyPair) {
        String currencyCode = currencyPair.base.getCurrencyCode().toLowerCase();
        String channel = String.format("ok_sub_futureusd_%s_index", currencyCode);
        return service.subscribeChannel(channel)
                .map(s -> {
                    ObjectMapper mapper = new ObjectMapper();
                    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                    mapper.registerModule(new JavaTimeModule());

                    return mapper.treeToValue(s.get("data"), FutureIndex.class);
                });
    }

}
