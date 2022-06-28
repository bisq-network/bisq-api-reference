/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */
package bisq.bots;

import bisq.proto.grpc.OfferInfo;
import io.grpc.StatusRuntimeException;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import protobuf.PaymentAccount;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.BiPredicate;

import static bisq.bots.BotUtils.*;
import static java.lang.String.format;
import static java.math.RoundingMode.HALF_UP;
import static protobuf.OfferDirection.BUY;

/**
 * Bot for buying BSQ with BTC at an attractive (lower) price.  The bot receives BSQ for BTC.
 */
@Slf4j
@Getter
public class TakeBestPricedOfferToSellBsq extends AbstractBot {

    // Taker bot's default BSQ payment account trading currency code.
    private static final String CURRENCY_CODE = "BSQ";

    // Config file:  resources/TakeBestPricedOfferToSellBsq.properties.
    private final Properties configFile;
    // Taker bot's default BSQ Swap payment account.
    private final PaymentAccount paymentAccount;
    // Taker bot's max market price margin.  A takeable BSQ Swap offer's fixed-price must be <= maxMarketPriceMargin (%).
    // Note:  all BSQ Swap offers have a fixed-price, but the bot uses a margin (%) of the 30-day price for comparison.
    private final BigDecimal maxMarketPriceMargin;
    // Hard coded 30-day average BSQ trade price, used for development over regtest (ignored when running on mainnet).
    private final BigDecimal regtest30DayAvgBsqPrice;
    // Taker bot's minimum BTC amount to trade.  A takeable offer's amount must be >= minAmount BTC.
    private final BigDecimal minAmount;
    // Taker bot's maximum BTC amount to trade.  A takeable offer's amount must be <= maxAmount BTC.
    private final BigDecimal maxAmount;
    // Taker bot's max acceptable transaction fee rate.
    private final long maxTxFeeRate;
    // Maximum # of offers to take during one bot session (shut down bot after taking N swap offers).
    private final int maxTakeOffers;

    // Offer polling frequency must be > 1000 ms between each getoffers request.
    private final long pollingInterval;

    // The # of offers taken during the bot session (since startup).
    private int numOffersTaken = 0;

    public TakeBestPricedOfferToSellBsq(String[] args) {
        super(args);
        pingDaemon(new Date().getTime()); // Shut down now if API daemon is not available.
        this.configFile = loadConfigFile();
        this.paymentAccount = getBsqSwapPaymentAccount();
        this.maxMarketPriceMargin = new BigDecimal(configFile.getProperty("maxMarketPriceMargin"))
                .setScale(2, HALF_UP);
        this.regtest30DayAvgBsqPrice = new BigDecimal(configFile.getProperty("regtest30DayAvgBsqPrice"))
                .setScale(8, HALF_UP);
        this.minAmount = new BigDecimal(configFile.getProperty("minAmount"));
        this.maxAmount = new BigDecimal(configFile.getProperty("maxAmount"));
        this.maxTxFeeRate = Long.parseLong(configFile.getProperty("maxTxFeeRate"));
        this.maxTakeOffers = Integer.parseInt(configFile.getProperty("maxTakeOffers"));
        loadPreferredOnionAddresses.accept(configFile, preferredTradingPeers);
        this.pollingInterval = Long.parseLong(configFile.getProperty("pollingInterval"));
    }

    /**
     * Checks for the most attractive BSQ Swap offer to take every {@link #pollingInterval} ms.
     * Will only terminate when manually shut down, or a fatal gRPC StatusRuntimeException is thrown.
     */
    @Override
    public void run() {
        var startTime = new Date().getTime();
        validateWalletPassword(walletPassword);
        validatePollingInterval(pollingInterval);
        printBotConfiguration();

        while (!isShutdown) {
            if (!isBisqNetworkTxFeeRateLowEnough.test(maxTxFeeRate)) {
                runCountdown(log, pollingInterval);
                continue;
            }

            // Get all available and takeable offers, sorted by price ascending.
            var offers = getOffers(BUY.name(), CURRENCY_CODE).stream()
                    .filter(o -> !isAlreadyTaken.test(o))
                    .toList();

            if (offers.isEmpty()) {
                log.info("No takeable offers found.");
                runCountdown(log, pollingInterval);
                continue;
            }

            // Define criteria for taking an offer, based on conf file.
            TakeBestPricedOfferToSellBsq.TakeCriteria takeCriteria = new TakeBestPricedOfferToSellBsq.TakeCriteria();
            takeCriteria.printCriteriaSummary();
            takeCriteria.printOffersAgainstCriteria(offers);

            // Find takeable offer based on criteria.
            Optional<OfferInfo> selectedOffer = takeCriteria.findTakeableOffer(offers);
            // Try to take the offer, if found, or say 'no offer found' before going to sleep.
            selectedOffer.ifPresentOrElse(offer -> takeOffer(takeCriteria, offer),
                    () -> {
                        var cheapestOffer = offers.get(0);
                        log.info("No acceptable offer found.  Closest possible candidate did not pass filters:");
                        takeCriteria.printOfferAgainstCriteria(cheapestOffer);
                    });

            printDryRunProgress();
            runCountdown(log, pollingInterval);
            pingDaemon(startTime);
        }
    }

    private void takeOffer(TakeCriteria takeCriteria, OfferInfo offer) {
        log.info("Will attempt to take offer '{}'.", offer.getId());
        takeCriteria.printOfferAgainstCriteria(offer);
        if (isDryRun) {
            addToOffersTaken(offer);
            numOffersTaken++;
            maybeShutdownAfterSuccessfulSwap(numOffersTaken, maxTakeOffers);
        } else {
            // An encrypted wallet must be unlocked before calling takeoffer and gettrade.
            // Unlock the wallet for 10 minutes.  If the wallet is already unlocked,
            // this command will override the timeout of the previous unlock command.
            try {
                unlockWallet(walletPassword, 600);

                printBTCBalances("BTC Balances Before Swap Execution");
                printBSQBalances("BSQ Balances Before Swap Execution");

                // Blocks until swap is executed, or times out.
                takeBsqSwapOffer(offer, pollingInterval);

                printBTCBalances("BTC Balances After Swap Execution");
                printBSQBalances("BSQ Balances After Swap Execution");

                numOffersTaken++;
                maybeShutdownAfterSuccessfulSwap(numOffersTaken, maxTakeOffers);
            } catch (NonFatalException nonFatalException) {
                handleNonFatalException(nonFatalException, pollingInterval);
            } catch (StatusRuntimeException fatalException) {
                handleFatalBsqSwapException(fatalException);
            }
        }
    }

    private void printBotConfiguration() {
        var configsByLabel = new LinkedHashMap<String, Object>();
        configsByLabel.put("Bot OS:", getOSName() + " " + getOSVersion());
        var network = getNetwork();
        configsByLabel.put("BTC Network:", network);
        var isMainnet = network.equalsIgnoreCase("mainnet");
        var mainnet30DayAvgBsqPrice = isMainnet ? get30DayAvgBsqPriceInBtc() : null;
        configsByLabel.put("My Payment Account:", "");
        configsByLabel.put("\tPayment Account Id:", paymentAccount.getId());
        configsByLabel.put("\tAccount Name:", paymentAccount.getAccountName());
        configsByLabel.put("\tCurrency Code:", CURRENCY_CODE);
        configsByLabel.put("Trading Rules:", "");
        configsByLabel.put("\tMax # of offers bot can take:", maxTakeOffers);
        configsByLabel.put("\tMax Tx Fee Rate:", maxTxFeeRate + " sats/byte");
        configsByLabel.put("\tMax Market Price Margin:", maxMarketPriceMargin + "%");
        if (isMainnet) {
            configsByLabel.put("\tMainnet 30-Day Avg BSQ Price:", mainnet30DayAvgBsqPrice + " BTC");
        } else {
            configsByLabel.put("\tRegtest 30-Day Avg BSQ Price:", regtest30DayAvgBsqPrice + " BTC");
        }
        configsByLabel.put("\tMin BTC Amount:", minAmount + " BTC");
        configsByLabel.put("\tMax BTC Amount: ", maxAmount + " BTC");
        if (iHavePreferredTradingPeers.get()) {
            configsByLabel.put("\tPreferred Trading Peers:", preferredTradingPeers.toString());
        } else {
            configsByLabel.put("\tPreferred Trading Peers:", "N/A");
        }
        configsByLabel.put("Bot Polling Interval:", pollingInterval + " ms");
        log.info(toTable.apply("Bot Configuration", configsByLabel));
    }

    /**
     * Return true is fixed-price offer's price <= the bot's max market price margin.  Allows bot to take a
     * fixed-priced offer if the price is <= {@link #maxMarketPriceMargin} (%) of the current market price.
     */
    protected final BiPredicate<OfferInfo, BigDecimal> isFixedPriceLEMaxMarketPriceMargin =
            (offer, currentMarketPrice) -> BotUtils.isFixedPriceLEMaxMarketPriceMargin(
                    offer,
                    currentMarketPrice,
                    getMaxMarketPriceMargin());

    public static void main(String[] args) {
        TakeBestPricedOfferToSellBsq bot = new TakeBestPricedOfferToSellBsq(args);
        bot.run();
    }

    /**
     * Calculates additional takeoffer criteria based on conf file values,
     * performs candidate offer filtering, and provides useful log statements.
     */
    private class TakeCriteria {
        private static final String MARKET_DESCRIPTION = "Sell BSQ (Buy BTC)";

        private final BigDecimal avgBsqPrice;
        @Getter
        private final BigDecimal targetPrice;

        public TakeCriteria() {
            this.avgBsqPrice = isConnectedToMainnet() ? get30DayAvgBsqPriceInBtc() : regtest30DayAvgBsqPrice;
            this.targetPrice = calcTargetBsqPrice(maxMarketPriceMargin, avgBsqPrice);
        }


        /**
         * Returns the lowest priced offer passing the filters, or Optional.empty() if not found.
         * Max tx fee rate filtering should have passed prior to calling this method.
         *
         * @param offers to filter
         */
        Optional<OfferInfo> findTakeableOffer(List<OfferInfo> offers) {
            if (iHavePreferredTradingPeers.get())
                return offers.stream()
                        .filter(o -> usesSamePaymentMethod.test(o, getPaymentAccount()))
                        .filter(isMakerPreferredTradingPeer)
                        .filter(o -> isFixedPriceLEMaxMarketPriceMargin.test(o, avgBsqPrice))
                        .filter(o -> isWithinBTCAmountBounds(o, getMinAmount(), getMaxAmount()))
                        .findFirst();
            else
                return offers.stream()
                        .filter(o -> usesSamePaymentMethod.test(o, getPaymentAccount()))
                        .filter(o -> isFixedPriceLEMaxMarketPriceMargin.test(o, avgBsqPrice))
                        .filter(o -> isWithinBTCAmountBounds(o, getMinAmount(), getMaxAmount()))
                        .findFirst();
        }

        void printCriteriaSummary() {
            if (isZero.test(maxMarketPriceMargin)) {
                log.info("Looking for offers to {}, with a fixed-price at or lower than"
                                + " the 30-day average BSQ trade price of {} BTC.",
                        MARKET_DESCRIPTION,
                        avgBsqPrice);
            } else {
                log.info("Looking for offers to {}, with a fixed-price at or lower than"
                                + " {}% {} the 30-day average BSQ trade price of {} BTC.",
                        MARKET_DESCRIPTION,
                        maxMarketPriceMargin.abs(), // Hide the sign, text explains target price % "above or below".
                        aboveOrBelowMaxMarketPriceMargin.apply(maxMarketPriceMargin),
                        avgBsqPrice);
            }
        }

        void printOffersAgainstCriteria(List<OfferInfo> offers) {
            log.info("Currently available {} offers -- want to take BSQ swap offer with fixed-price <= {} BTC.",
                    MARKET_DESCRIPTION,
                    targetPrice);
            printOffersSummary(offers);
        }

        void printOfferAgainstCriteria(OfferInfo offer) {
            printOfferSummary(offer);

            var filterResultsByLabel = new LinkedHashMap<String, Object>();
            filterResultsByLabel.put("30-day Avg BSQ trade price:", avgBsqPrice + " BTC");
            filterResultsByLabel.put("Target Price (Min):", targetPrice + " BTC");
            filterResultsByLabel.put("Offer Price:", offer.getPrice() + " BTC");
            filterResultsByLabel.put("Offer maker used same payment method?",
                    usesSamePaymentMethod.test(offer, getPaymentAccount()));
            filterResultsByLabel.put("Is offer's maker a preferred trading peer?",
                    iHavePreferredTradingPeers.get()
                            ? isMakerPreferredTradingPeer.test(offer) ? "YES" : "NO"
                            : "N/A");
            var fixedPriceLabel = format("Is offer's fixed-price (%s) <= bot's minimum price (%s)?",
                    offer.getPrice() + " BTC",
                    targetPrice + " BTC");
            filterResultsByLabel.put(fixedPriceLabel, isFixedPriceLEMaxMarketPriceMargin.test(offer, avgBsqPrice));
            var btcAmountBounds = format("%s BTC - %s BTC", minAmount, maxAmount);
            filterResultsByLabel.put("Is offer's BTC amount within bot amount bounds (" + btcAmountBounds + ")?",
                    isWithinBTCAmountBounds(offer, getMinAmount(), getMaxAmount()));

            var title = format("Fixed price BSQ swap offer %s filter results:", offer.getId());
            log.info(toTable.apply(title, filterResultsByLabel));
        }
    }
}
