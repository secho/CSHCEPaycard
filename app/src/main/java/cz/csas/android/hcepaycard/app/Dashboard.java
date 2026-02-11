package cz.csas.android.hcepaycard.app;

import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.nfc.FormatException;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.cardemulation.CardEmulation;
import android.nfc.tech.IsoDep;
import android.nfc.tech.Ndef;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.Log;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import services.MyHostApduService;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static cz.csas.android.hcepaycard.app.Constants.ACTIVE_CARD_ID_PREF_KEY;
import static cz.csas.android.hcepaycard.app.Constants.DEFAULT_SWIPE_DATA;
import static cz.csas.android.hcepaycard.app.Constants.STORED_CARDS_PREF_KEY;
import static cz.csas.android.hcepaycard.app.Constants.SWIPE_DATA_PREF_KEY;

public class Dashboard extends AppCompatActivity {

    private static final String TAG = "Dashboard";
    private static final String SELECT_PPSE_COMMAND_HEX = "00A404000E325041592E5359532E444446303100";
    private static final Pattern TRACK2_PATTERN = Pattern.compile(".*;(\\d{12,19}=\\d{1,128})\\?.*");
    private static final Charset UTF8 = StandardCharsets.UTF_8;

    private TextView statusTextView;
    private TextView scannedDataTextView;
    private TextView selectedCardDetailsTextView;
    private EditText cardNameEditText;
    private EditText swipeDataEditText;
    private Button scanButton;
    private Button defaultWalletButton;
    private Button saveCardButton;
    private Button useSelectedCardButton;
    private Button deleteSelectedCardButton;
    private Button clearAllCardsButton;
    private ListView storedCardsListView;

    private SharedPreferences prefs;
    private NfcAdapter nfcAdapter;
    private boolean readerEnabled;
    private ScannedCardData lastScannedCard;
    private String selectedCardId = "";

    private final List<StoredCard> storedCards = new ArrayList<StoredCard>();
    private final List<String> storedCardsRows = new ArrayList<String>();
    private ArrayAdapter<String> cardsAdapter;

    private final NfcAdapter.ReaderCallback readerCallback = new NfcAdapter.ReaderCallback() {
        @Override
        public void onTagDiscovered(final Tag tag) {
            final ScannedCardData scanResult = scanTag(tag);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    onCardScanned(scanResult);
                }
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);

        bindViews();
        setupStoredCardsList();
        setupButtons();

        prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        loadStoredCards();
        selectedCardId = prefs.getString(ACTIVE_CARD_ID_PREF_KEY, "");
        refreshStoredCardsUi();

        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        if (nfcAdapter == null) {
            setStatus(getString(R.string.status_nfc_unavailable));
            disableNfcActions();
            return;
        }

        setStatus(getString(R.string.status_ready));
        requestDefaultPaymentApp(false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopReaderMode();
    }

    private void bindViews() {
        statusTextView = findViewById(R.id.tvStatus);
        scannedDataTextView = findViewById(R.id.tvScannedCardData);
        selectedCardDetailsTextView = findViewById(R.id.tvSelectedCardDetails);
        cardNameEditText = findViewById(R.id.etCardName);
        swipeDataEditText = findViewById(R.id.etSwipeData);
        scanButton = findViewById(R.id.btnScanCard);
        defaultWalletButton = findViewById(R.id.btnDefaultWallet);
        saveCardButton = findViewById(R.id.btnSaveCard);
        useSelectedCardButton = findViewById(R.id.btnUseSelectedCard);
        deleteSelectedCardButton = findViewById(R.id.btnDeleteSelectedCard);
        clearAllCardsButton = findViewById(R.id.btnClearAllCards);
        storedCardsListView = findViewById(R.id.listStoredCards);
    }

    private void setupStoredCardsList() {
        cardsAdapter = new ArrayAdapter<String>(
                this,
                android.R.layout.simple_list_item_activated_1,
                storedCardsRows
        );
        storedCardsListView.setAdapter(cardsAdapter);
        storedCardsListView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        storedCardsListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, android.view.View view, int position, long id) {
                if (position < 0 || position >= storedCards.size()) {
                    return;
                }
                StoredCard selected = storedCards.get(position);
                selectedCardId = selected.id;
                cardNameEditText.setText(selected.name);
                swipeDataEditText.setText(selected.swipeData);
                updateSelectedCardDetails();
            }
        });
    }

    private void setupButtons() {
        scanButton.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                if (readerEnabled) {
                    stopReaderMode();
                    setStatus(getString(R.string.status_scan_stopped));
                } else {
                    startReaderMode();
                }
            }
        });

        defaultWalletButton.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                requestDefaultPaymentApp(true);
            }
        });

        saveCardButton.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                saveScannedCard();
            }
        });

        useSelectedCardButton.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                activateSelectedCardForPayment();
            }
        });

        deleteSelectedCardButton.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                deleteSelectedCard();
            }
        });

        clearAllCardsButton.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                clearAllCards();
            }
        });
    }

    private void disableNfcActions() {
        scanButton.setEnabled(false);
        defaultWalletButton.setEnabled(false);
    }

    private void setStatus(String message) {
        statusTextView.setText(message);
    }

    private void startReaderMode() {
        if (nfcAdapter == null) {
            setStatus(getString(R.string.status_nfc_unavailable));
            return;
        }
        Bundle options = new Bundle();
        options.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 150);
        int flags = NfcAdapter.FLAG_READER_NFC_A
                | NfcAdapter.FLAG_READER_NFC_B
                | NfcAdapter.FLAG_READER_NFC_F
                | NfcAdapter.FLAG_READER_NFC_V
                | NfcAdapter.FLAG_READER_NFC_BARCODE;

        nfcAdapter.enableReaderMode(this, readerCallback, flags, options);
        readerEnabled = true;
        scanButton.setText(R.string.button_stop_scan);
        setStatus(getString(R.string.status_scanning));
    }

    private void stopReaderMode() {
        if (nfcAdapter != null && readerEnabled) {
            nfcAdapter.disableReaderMode(this);
        }
        readerEnabled = false;
        scanButton.setText(R.string.button_scan_card);
    }

    private void requestDefaultPaymentApp(boolean userInitiated) {
        if (nfcAdapter == null) {
            return;
        }
        CardEmulation cardEmulationManager;
        try {
            cardEmulationManager = CardEmulation.getInstance(nfcAdapter);
        } catch (UnsupportedOperationException ex) {
            Log.w(TAG, "Card emulation is not supported on this device.", ex);
            return;
        }

        ComponentName paymentServiceComponent = new ComponentName(this, MyHostApduService.class);
        if (cardEmulationManager.isDefaultServiceForCategory(paymentServiceComponent, CardEmulation.CATEGORY_PAYMENT)) {
            if (userInitiated) {
                setStatus(getString(R.string.status_already_default_wallet));
            }
            return;
        }

        Intent intent = new Intent(CardEmulation.ACTION_CHANGE_DEFAULT);
        intent.putExtra(CardEmulation.EXTRA_CATEGORY, CardEmulation.CATEGORY_PAYMENT);
        intent.putExtra(CardEmulation.EXTRA_SERVICE_COMPONENT, paymentServiceComponent);
        startActivity(intent);
        if (userInitiated) {
            setStatus(getString(R.string.status_default_wallet_requested));
        }
    }

    private void onCardScanned(ScannedCardData scanResult) {
        lastScannedCard = scanResult;
        scannedDataTextView.setText(scanResult.toDisplayString(this));

        if (TextUtils.isEmpty(cardNameEditText.getText().toString().trim())) {
            cardNameEditText.setText(getString(R.string.card_prefix) + " " + scanResult.getShortUid());
        }
        if (TextUtils.isEmpty(swipeDataEditText.getText().toString().trim())
                && !TextUtils.isEmpty(scanResult.swipeDataCandidate)) {
            swipeDataEditText.setText(scanResult.swipeDataCandidate);
        }
        stopReaderMode();
        setStatus(getString(R.string.status_card_scanned));
    }

    private void saveScannedCard() {
        if (lastScannedCard == null) {
            setStatus(getString(R.string.status_no_scanned_card));
            return;
        }

        String cardName = cardNameEditText.getText().toString().trim();
        if (TextUtils.isEmpty(cardName)) {
            cardName = getString(R.string.card_prefix) + " " + String.valueOf(storedCards.size() + 1);
        }

        String swipeData = swipeDataEditText.getText().toString().trim();
        if (TextUtils.isEmpty(swipeData)) {
            swipeData = DEFAULT_SWIPE_DATA;
        }
        if (!isSwipeDataValid(swipeData)) {
            setStatus(getString(R.string.status_invalid_swipe_data));
            Toast.makeText(this, R.string.status_invalid_swipe_data, Toast.LENGTH_LONG).show();
            return;
        }

        StoredCard newCard = StoredCard.fromScan(cardName, swipeData, lastScannedCard);
        storedCards.add(0, newCard);
        selectedCardId = newCard.id;
        persistStoredCards();
        refreshStoredCardsUi();

        setStatus(getString(R.string.status_card_saved));
    }

    private void activateSelectedCardForPayment() {
        StoredCard selectedCard = getSelectedCard();
        if (selectedCard == null) {
            setStatus(getString(R.string.status_no_card_selected));
            return;
        }

        prefs.edit()
                .putString(SWIPE_DATA_PREF_KEY, selectedCard.swipeData)
                .putString(ACTIVE_CARD_ID_PREF_KEY, selectedCard.id)
                .apply();
        selectedCardId = selectedCard.id;
        refreshStoredCardsUi();
        setStatus(getString(R.string.status_card_activated));
    }

    private void deleteSelectedCard() {
        StoredCard selectedCard = getSelectedCard();
        if (selectedCard == null) {
            setStatus(getString(R.string.status_no_card_selected));
            return;
        }

        storedCards.remove(selectedCard);
        if (selectedCard.id.equals(prefs.getString(ACTIVE_CARD_ID_PREF_KEY, ""))) {
            prefs.edit()
                    .remove(ACTIVE_CARD_ID_PREF_KEY)
                    .putString(SWIPE_DATA_PREF_KEY, DEFAULT_SWIPE_DATA)
                    .apply();
            selectedCardId = "";
        } else if (selectedCard.id.equals(selectedCardId)) {
            selectedCardId = "";
        }

        persistStoredCards();
        refreshStoredCardsUi();
        setStatus(getString(R.string.status_card_deleted));
    }

    private void clearAllCards() {
        storedCards.clear();
        selectedCardId = "";
        prefs.edit()
                .remove(STORED_CARDS_PREF_KEY)
                .remove(ACTIVE_CARD_ID_PREF_KEY)
                .putString(SWIPE_DATA_PREF_KEY, DEFAULT_SWIPE_DATA)
                .apply();
        refreshStoredCardsUi();
        setStatus(getString(R.string.status_all_cards_cleared));
    }

    private StoredCard getSelectedCard() {
        if (TextUtils.isEmpty(selectedCardId)) {
            return null;
        }
        for (StoredCard storedCard : storedCards) {
            if (selectedCardId.equals(storedCard.id)) {
                return storedCard;
            }
        }
        return null;
    }

    private void refreshStoredCardsUi() {
        storedCardsRows.clear();
        String activeCardId = prefs != null ? prefs.getString(ACTIVE_CARD_ID_PREF_KEY, "") : "";

        for (StoredCard storedCard : storedCards) {
            storedCardsRows.add(storedCard.toListRow(this, storedCard.id.equals(activeCardId)));
        }

        cardsAdapter.clear();
        cardsAdapter.addAll(storedCardsRows);
        cardsAdapter.notifyDataSetChanged();

        syncListSelection();
        updateSelectedCardDetails();
    }

    private void syncListSelection() {
        storedCardsListView.clearChoices();
        if (TextUtils.isEmpty(selectedCardId)) {
            return;
        }
        for (int i = 0; i < storedCards.size(); i++) {
            if (selectedCardId.equals(storedCards.get(i).id)) {
                storedCardsListView.setItemChecked(i, true);
                break;
            }
        }
    }

    private void updateSelectedCardDetails() {
        StoredCard selectedCard = getSelectedCard();
        if (selectedCard == null) {
            selectedCardDetailsTextView.setText(R.string.placeholder_no_selected_card);
            return;
        }
        selectedCardDetailsTextView.setText(selectedCard.toDetailsText(this));
    }

    private void loadStoredCards() {
        storedCards.clear();
        String json = prefs.getString(STORED_CARDS_PREF_KEY, "[]");
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                StoredCard storedCard = StoredCard.fromJson(obj);
                storedCards.add(storedCard);
            }
        } catch (JSONException ex) {
            Log.w(TAG, "Failed to parse stored cards JSON.", ex);
        }
    }

    private void persistStoredCards() {
        JSONArray arr = new JSONArray();
        for (StoredCard storedCard : storedCards) {
            arr.put(storedCard.toJson());
        }
        prefs.edit().putString(STORED_CARDS_PREF_KEY, arr.toString()).apply();
    }

    private ScannedCardData scanTag(Tag tag) {
        ScannedCardData result = new ScannedCardData();
        result.uidHex = tag.getId() == null ? getString(R.string.detail_none) : Util.byteArrayToHex(tag.getId());
        result.techSummary = formatTechList(tag.getTechList());
        result.aidsSummary = getString(R.string.detail_none);
        result.ndefSummary = getString(R.string.detail_none);
        result.ppseResponseHex = getString(R.string.detail_none);
        result.notes = getString(R.string.detail_none);

        StringBuilder notesBuilder = new StringBuilder();
        readNdefData(tag, result, notesBuilder);
        readIsoDepData(tag, result, notesBuilder);

        if (notesBuilder.length() > 0) {
            result.notes = notesBuilder.toString();
        }
        if (result.notes.length() == 0) {
            result.notes = getString(R.string.detail_none);
        }

        return result;
    }

    private void readNdefData(Tag tag, ScannedCardData result, StringBuilder notesBuilder) {
        Ndef ndef = Ndef.get(tag);
        if (ndef == null) {
            return;
        }
        try {
            ndef.connect();
            NdefMessage ndefMessage = ndef.getCachedNdefMessage();
            if (ndefMessage == null) {
                ndefMessage = ndef.getNdefMessage();
            }
            if (ndefMessage == null || ndefMessage.getRecords() == null || ndefMessage.getRecords().length == 0) {
                appendNote(notesBuilder, "NDEF present but no records.");
                return;
            }

            List<String> recordTexts = new ArrayList<String>();
            for (NdefRecord record : ndefMessage.getRecords()) {
                String decoded = decodeNdefRecord(record);
                if (!TextUtils.isEmpty(decoded)) {
                    recordTexts.add(decoded);
                }
            }
            if (recordTexts.isEmpty()) {
                appendNote(notesBuilder, "Unable to decode NDEF records.");
                return;
            }

            result.ndefSummary = TextUtils.join(" | ", recordTexts);
            String candidate = extractSwipeDataCandidate(result.ndefSummary);
            if (!TextUtils.isEmpty(candidate)) {
                result.swipeDataCandidate = candidate;
            }
        } catch (IOException ex) {
            appendNote(notesBuilder, "NDEF read failed: " + ex.getMessage());
        } catch (FormatException ex) {
            appendNote(notesBuilder, "NDEF format error: " + ex.getMessage());
        } finally {
            closeQuietly(ndef);
        }
    }

    private void readIsoDepData(Tag tag, ScannedCardData result, StringBuilder notesBuilder) {
        IsoDep isoDep = IsoDep.get(tag);
        if (isoDep == null) {
            return;
        }
        try {
            isoDep.connect();
            isoDep.setTimeout(2000);

            byte[] ppseResponse = isoDep.transceive(Util.hexToByteArray(SELECT_PPSE_COMMAND_HEX));
            result.ppseResponseHex = Util.byteArrayToHex(ppseResponse);
            if (!isSuccessResponse(ppseResponse)) {
                appendNote(notesBuilder, "PPSE select did not return 9000.");
                return;
            }

            List<String> aids = extractAidValues(ppseResponse);
            if (aids.isEmpty()) {
                appendNote(notesBuilder, "No AID found in PPSE response.");
                return;
            }

            List<String> labeledAids = new ArrayList<String>();
            for (String aid : aids) {
                String label = readApplicationLabel(isoDep, aid);
                if (!TextUtils.isEmpty(label)) {
                    labeledAids.add(aid + " (" + label + ")");
                } else {
                    labeledAids.add(aid);
                }
            }
            result.aidsSummary = TextUtils.join(", ", labeledAids);
        } catch (IOException ex) {
            appendNote(notesBuilder, "IsoDep read failed: " + ex.getMessage());
        } finally {
            closeQuietly(isoDep);
        }
    }

    private String readApplicationLabel(IsoDep isoDep, String aidHex) throws IOException {
        byte[] command = buildSelectAidCommand(aidHex);
        byte[] response = isoDep.transceive(command);
        if (!isSuccessResponse(response)) {
            return null;
        }
        return extractAsciiTlvValue(response, 0x50);
    }

    private static byte[] buildSelectAidCommand(String aidHex) {
        byte[] aidBytes = Util.hexToByteArray(aidHex);
        byte[] command = new byte[6 + aidBytes.length];
        command[0] = 0x00;
        command[1] = (byte) 0xA4;
        command[2] = 0x04;
        command[3] = 0x00;
        command[4] = (byte) aidBytes.length;
        System.arraycopy(aidBytes, 0, command, 5, aidBytes.length);
        command[command.length - 1] = 0x00;
        return command;
    }

    private static String extractAsciiTlvValue(byte[] response, int tag) {
        if (response == null || response.length < 4) {
            return null;
        }
        int limit = Math.max(0, response.length - 2);
        for (int i = 0; i + 2 <= limit; i++) {
            if ((response[i] & 0xFF) == tag) {
                int length = response[i + 1] & 0xFF;
                int valueStart = i + 2;
                int valueEnd = valueStart + length;
                if (valueEnd <= limit) {
                    return new String(Arrays.copyOfRange(response, valueStart, valueEnd), UTF8).trim();
                }
            }
        }
        return null;
    }

    private static List<String> extractAidValues(byte[] response) {
        Set<String> aids = new LinkedHashSet<String>();
        if (response == null || response.length < 4) {
            return new ArrayList<String>();
        }

        int limit = Math.max(0, response.length - 2);
        for (int i = 0; i + 2 <= limit; i++) {
            if ((response[i] & 0xFF) != 0x4F) {
                continue;
            }
            int length = response[i + 1] & 0xFF;
            int valueStart = i + 2;
            int valueEnd = valueStart + length;
            if (length <= 0 || valueEnd > limit) {
                continue;
            }
            aids.add(Util.byteArrayToHex(Arrays.copyOfRange(response, valueStart, valueEnd)));
            i = valueEnd - 1;
        }

        return new ArrayList<String>(aids);
    }

    private static boolean isSuccessResponse(byte[] response) {
        return response != null
                && response.length >= 2
                && response[response.length - 2] == (byte) 0x90
                && response[response.length - 1] == (byte) 0x00;
    }

    private static String decodeNdefRecord(NdefRecord record) {
        if (record == null || record.getPayload() == null) {
            return null;
        }

        short tnf = record.getTnf();
        byte[] payload = record.getPayload();
        if (tnf == NdefRecord.TNF_WELL_KNOWN && Arrays.equals(record.getType(), NdefRecord.RTD_TEXT)) {
            if (payload.length == 0) {
                return null;
            }
            int languageCodeLength = payload[0] & 0x3F;
            int textStart = 1 + languageCodeLength;
            if (textStart > payload.length) {
                return null;
            }
            Charset encoding = (payload[0] & 0x80) == 0 ? StandardCharsets.UTF_8 : StandardCharsets.UTF_16;
            return new String(payload, textStart, payload.length - textStart, encoding);
        }

        if (tnf == NdefRecord.TNF_WELL_KNOWN && Arrays.equals(record.getType(), NdefRecord.RTD_URI)) {
            return record.toUri() != null ? record.toUri().toString() : null;
        }

        return Util.byteArrayToHex(payload);
    }

    private static String extractSwipeDataCandidate(String text) {
        if (TextUtils.isEmpty(text)) {
            return null;
        }
        Matcher matcher = TRACK2_PATTERN.matcher(text);
        if (matcher.matches()) {
            return text;
        }

        Matcher track2OnlyMatcher = Pattern.compile("(;\\d{12,19}=\\d{1,128}\\?)").matcher(text);
        if (track2OnlyMatcher.find()) {
            return track2OnlyMatcher.group(1);
        }
        return null;
    }

    private static String formatTechList(String[] techList) {
        if (techList == null || techList.length == 0) {
            return "-";
        }
        List<String> simpleNames = new ArrayList<String>();
        for (String tech : techList) {
            if (tech == null) {
                continue;
            }
            int lastDot = tech.lastIndexOf('.');
            simpleNames.add(lastDot >= 0 ? tech.substring(lastDot + 1) : tech);
        }
        if (simpleNames.isEmpty()) {
            return "-";
        }
        return TextUtils.join(", ", simpleNames);
    }

    private static void closeQuietly(IsoDep isoDep) {
        if (isoDep == null) {
            return;
        }
        try {
            isoDep.close();
        } catch (IOException ignored) {
            // Ignored on cleanup.
        }
    }

    private static void closeQuietly(Ndef ndef) {
        if (ndef == null) {
            return;
        }
        try {
            ndef.close();
        } catch (IOException ignored) {
            // Ignored on cleanup.
        }
    }

    private static void appendNote(StringBuilder notesBuilder, String note) {
        if (notesBuilder.length() > 0) {
            notesBuilder.append(" | ");
        }
        notesBuilder.append(note);
    }

    private static boolean isSwipeDataValid(String swipeData) {
        return !TextUtils.isEmpty(swipeData) && TRACK2_PATTERN.matcher(swipeData).matches();
    }

    private static String valueOrDash(String value) {
        return TextUtils.isEmpty(value) ? "-" : value;
    }

    private static final class ScannedCardData {
        String uidHex;
        String techSummary;
        String aidsSummary;
        String ndefSummary;
        String ppseResponseHex;
        String notes;
        String swipeDataCandidate;

        String getShortUid() {
            if (TextUtils.isEmpty(uidHex) || "-".equals(uidHex)) {
                return "N/A";
            }
            return uidHex.length() > 8 ? uidHex.substring(uidHex.length() - 8) : uidHex;
        }

        String toDisplayString(Dashboard dashboard) {
            StringBuilder sb = new StringBuilder();
            sb.append(dashboard.getString(R.string.scan_data_uid)).append(": ").append(valueOrDash(uidHex)).append("\n");
            sb.append(dashboard.getString(R.string.scan_data_tech)).append(": ").append(valueOrDash(techSummary)).append("\n");
            sb.append(dashboard.getString(R.string.scan_data_aids)).append(": ").append(valueOrDash(aidsSummary)).append("\n");
            sb.append(dashboard.getString(R.string.scan_data_ndef)).append(": ").append(valueOrDash(ndefSummary)).append("\n");
            sb.append(dashboard.getString(R.string.scan_data_ppse_response)).append(": ").append(valueOrDash(ppseResponseHex)).append("\n");
            sb.append(dashboard.getString(R.string.scan_data_notes)).append(": ").append(valueOrDash(notes));
            return sb.toString();
        }
    }

    private static final class StoredCard {
        String id;
        String name;
        String uidHex;
        String techSummary;
        String aidsSummary;
        String ndefSummary;
        String ppseResponseHex;
        String notes;
        String swipeData;
        long savedAt;

        static StoredCard fromScan(String cardName, String swipeData, ScannedCardData scanned) {
            StoredCard storedCard = new StoredCard();
            storedCard.id = UUID.randomUUID().toString();
            storedCard.name = cardName;
            storedCard.uidHex = scanned.uidHex;
            storedCard.techSummary = scanned.techSummary;
            storedCard.aidsSummary = scanned.aidsSummary;
            storedCard.ndefSummary = scanned.ndefSummary;
            storedCard.ppseResponseHex = scanned.ppseResponseHex;
            storedCard.notes = scanned.notes;
            storedCard.swipeData = swipeData;
            storedCard.savedAt = System.currentTimeMillis();
            return storedCard;
        }

        JSONObject toJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("id", id);
                obj.put("name", name);
                obj.put("uidHex", uidHex);
                obj.put("techSummary", techSummary);
                obj.put("aidsSummary", aidsSummary);
                obj.put("ndefSummary", ndefSummary);
                obj.put("ppseResponseHex", ppseResponseHex);
                obj.put("notes", notes);
                obj.put("swipeData", swipeData);
                obj.put("savedAt", savedAt);
            } catch (JSONException ignored) {
                // Kept for forward compatibility; all values are primitive/string.
            }
            return obj;
        }

        static StoredCard fromJson(JSONObject obj) {
            StoredCard storedCard = new StoredCard();
            storedCard.id = obj.optString("id", UUID.randomUUID().toString());
            storedCard.name = obj.optString("name", "Card");
            storedCard.uidHex = obj.optString("uidHex", "-");
            storedCard.techSummary = obj.optString("techSummary", "-");
            storedCard.aidsSummary = obj.optString("aidsSummary", "-");
            storedCard.ndefSummary = obj.optString("ndefSummary", "-");
            storedCard.ppseResponseHex = obj.optString("ppseResponseHex", "-");
            storedCard.notes = obj.optString("notes", "-");
            storedCard.swipeData = obj.optString("swipeData", DEFAULT_SWIPE_DATA);
            storedCard.savedAt = obj.optLong("savedAt", System.currentTimeMillis());
            return storedCard;
        }

        String toListRow(Dashboard dashboard, boolean active) {
            StringBuilder row = new StringBuilder();
            if (active) {
                row.append("[").append(dashboard.getString(R.string.label_active_card)).append("] ");
            }
            row.append(valueOrDash(name))
                    .append(dashboard.getString(R.string.separator_bullet))
                    .append(valueOrDash(uidHex));
            return row.toString();
        }

        String toDetailsText(Dashboard dashboard) {
            StringBuilder sb = new StringBuilder();
            DateFormat dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
            sb.append(dashboard.getString(R.string.detail_name)).append(": ").append(valueOrDash(name)).append("\n");
            sb.append(dashboard.getString(R.string.detail_saved)).append(": ")
                    .append(dateFormat.format(new Date(savedAt))).append("\n");
            sb.append(dashboard.getString(R.string.scan_data_uid)).append(": ").append(valueOrDash(uidHex)).append("\n");
            sb.append(dashboard.getString(R.string.scan_data_tech)).append(": ").append(valueOrDash(techSummary)).append("\n");
            sb.append(dashboard.getString(R.string.scan_data_aids)).append(": ").append(valueOrDash(aidsSummary)).append("\n");
            sb.append(dashboard.getString(R.string.scan_data_ndef)).append(": ").append(valueOrDash(ndefSummary)).append("\n");
            sb.append(dashboard.getString(R.string.scan_data_ppse_response)).append(": ")
                    .append(valueOrDash(ppseResponseHex)).append("\n");
            sb.append(dashboard.getString(R.string.detail_swipe_data)).append(": ").append(valueOrDash(swipeData)).append("\n");
            sb.append(dashboard.getString(R.string.scan_data_notes)).append(": ").append(valueOrDash(notes));
            return sb.toString();
        }
    }
}
