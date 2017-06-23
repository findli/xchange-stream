package info.bitrich.xchangestream.okex;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import info.bitrich.xchangestream.core.StreamingPrivateDataService;
import info.bitrich.xchangestream.core.dto.PrivateData;
import info.bitrich.xchangestream.okcoin.OkCoinAuthSigner;
import info.bitrich.xchangestream.okcoin.OkCoinStreamingService;
import info.bitrich.xchangestream.okex.dto.BalanceEx;
import info.bitrich.xchangestream.okex.dto.OkExTradeResult;

import org.knowm.xchange.Exchange;
import org.knowm.xchange.dto.account.AccountInfo;
import org.knowm.xchange.dto.account.Wallet;
import org.knowm.xchange.dto.trade.LimitOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.reactivex.Observable;

/**
 * Created by Sergey Shurmin on 6/18/17.
 */
public class OkExStreamingPrivateDataService implements StreamingPrivateDataService {
    private static final Logger logger = LoggerFactory.getLogger(OkExStreamingPrivateDataService.class);

    private final OkCoinStreamingService service;
    private final Exchange exchange;

    OkExStreamingPrivateDataService(OkCoinStreamingService service, Exchange exchange) {
        this.service = service;
        this.exchange = exchange;
    }

    @Override
    public Observable<PrivateData> getAllPrivateDataObservable() {
        final String apiKey = exchange.getExchangeSpecification().getApiKey();
        final String secretKey = exchange.getExchangeSpecification().getSecretKey();
        final OkCoinAuthSigner signer = new OkCoinAuthSigner(apiKey, secretKey);
        final Map<String, String> nameValueMap = new HashMap<>();
        final String sign = signer.digestParams(nameValueMap);

        // The same info for all subscriptions:
        // Successful response for all: [{"data":{"result":"true"},"channel":"login"}]
        return service.subscribeBatchChannels("ok_sub_futureusd_userinfo",
                Arrays.asList("ok_sub_futureusd_userinfo",
                        "ok_sub_futureusd_positions",
                        "ok_sub_futureusd_trades"),
                apiKey,
                sign)
                .map(this::parseResult);
    }

    PrivateData parseResult(JsonNode jsonNode) throws Exception {

        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.registerModule(new JavaTimeModule());

        List<LimitOrder> trades = new ArrayList<>();
        AccountInfo accountInfo = null;

        final JsonNode channel = jsonNode.get("channel");
        if (channel != null) {
            final JsonNode dataNode = jsonNode.get("data");

            System.out.println(channel.asText() + ":" + dataNode.toString());

            switch (channel.asText()) {
                case "ok_sub_futureusd_trades":
                    // TODO parse future trades
                    final OkExTradeResult okExTradeResult = mapper.treeToValue(dataNode, OkExTradeResult.class);
                    final LimitOrder limitOrder = OkExAdapters.adaptTradeResult(okExTradeResult);
                    trades.add(limitOrder);
                    break;
                case "ok_sub_futureusd_userinfo":
                    // TODO parse user info
                    final BigDecimal total = new BigDecimal(dataNode.get("balance").asText());
                    final BigDecimal amountStr = new BigDecimal(dataNode.get("unit_amount").asText());
                    final BigDecimal profitReal = new BigDecimal(dataNode.get("profit_real").asText());
                    final BigDecimal equity = new BigDecimal(dataNode.get("keep_deposit").asText());
                    final BigDecimal available = total.subtract(equity);
                    final BalanceEx balance = new BalanceEx(OkExAdapters.WALLET_CURRENCY,
                            total,
                            available,
                            equity);
                    balance.setRaw(dataNode.toString());
                    accountInfo = new AccountInfo(new Wallet(balance));
                    break;
                case "ok_sub_futureusd_positions":
                    final JsonNode positionsNode = dataNode.get("positions");
                    accountInfo = adaptPosition(positionsNode);
                    break;
                default:
                    System.out.println("Warning unknown response channel");
            }
        }

        return new PrivateData(trades, accountInfo);
    }

    private AccountInfo adaptPosition(JsonNode positionsNode) {
        logger.info(positionsNode.toString());
        BalanceEx balance1 = BalanceEx.zero(OkExAdapters.POSITION_LONG_CURRENCY);
        BalanceEx balance2 = BalanceEx.zero(OkExAdapters.POSITION_SHORT_CURRENCY);
        for (JsonNode node : positionsNode) {

            final String contractName = node.get("contract_name").asText();
            final String bondfreez = node.get("bondfreez").asText();
            final String position = node.get("position").asText();
            if (position.equals("1")) { // long - buy - bid
                balance1 = new BalanceEx(OkExAdapters.POSITION_LONG_CURRENCY,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        new BigDecimal(bondfreez)
                );
                balance1.setRaw(positionsNode.toString());
            } else if (position.equals("2")) { // short - sell - ask
                balance2 = new BalanceEx(OkExAdapters.POSITION_SHORT_CURRENCY,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        new BigDecimal(bondfreez)
                );
                balance2.setRaw(positionsNode.toString());
            }
        }

        return new AccountInfo(new Wallet(balance1, balance2));
    }

}
