package cz.csas.android.hcepaycard.app;

public class Constants {

    //
    //  Visa MSD predplacenka, mela by prochazet na vsechny validace, jedna se o swipe data - vybrakovany z netu
    //
    public static final String DEFAULT_SWIPE_DATA = "%B4046460664629718^000NETSPEND^161012100000181000000?;4046460664629718=16101210000018100000?";

    //
    //  klic pro app preference
    //
    public static final String SWIPE_DATA_PREF_KEY = "SWIPE_DATA";

    // Stored cards JSON payload in preferences.
    public static final String STORED_CARDS_PREF_KEY = "STORED_CARDS_JSON";

    // Active card identifier used for HCE emulation selection.
    public static final String ACTIVE_CARD_ID_PREF_KEY = "ACTIVE_CARD_ID";
}
