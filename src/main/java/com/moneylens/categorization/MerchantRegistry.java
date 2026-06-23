package com.moneylens.categorization;

import com.moneylens.entity.Category;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Rule-based merchant → category lookup.
 *
 * Entries are checked as substrings of a lowercased haystack built from
 * (merchantName + " " + rawNarration). More specific entries MUST appear
 * before broader ones — insertion order is the priority order.
 *
 * Naming rules:
 *   - All keys lowercase
 *   - Prefer the most specific string that won't false-match (≥ 4 chars usually)
 *   - Add brand variants: full name, short name, common misspellings
 */
@Component
public class MerchantRegistry {

    private static final Map<String, Category> MERCHANT_MAP = new LinkedHashMap<>();

    static {

        // ─────────────────────────────────────────────────────────────────────
        // FOOD & DINING
        // ─────────────────────────────────────────────────────────────────────

        // Delivery platforms
        MERCHANT_MAP.put("swiggy",            Category.FOOD_AND_DINING);
        MERCHANT_MAP.put("zomato",            Category.FOOD_AND_DINING);

        // Fast food / QSR chains
        MERCHANT_MAP.put("dominos",           Category.FOOD_AND_DINING);
        MERCHANT_MAP.put("dominoes",          Category.FOOD_AND_DINING);
        MERCHANT_MAP.put("domino's",          Category.FOOD_AND_DINING);
        MERCHANT_MAP.put("pizza hut",         Category.FOOD_AND_DINING);
        MERCHANT_MAP.put("pizzahut",          Category.FOOD_AND_DINING);
        MERCHANT_MAP.put("kfc",               Category.FOOD_AND_DINING);
        MERCHANT_MAP.put("mcdonalds",         Category.FOOD_AND_DINING);
        MERCHANT_MAP.put("mcdonald",          Category.FOOD_AND_DINING);
        MERCHANT_MAP.put("burger king",       Category.FOOD_AND_DINING);
        MERCHANT_MAP.put("burgerking",        Category.FOOD_AND_DINING);
        MERCHANT_MAP.put("subway",            Category.FOOD_AND_DINING);
        MERCHANT_MAP.put("taco bell",         Category.FOOD_AND_DINING);
        MERCHANT_MAP.put("tacobell",          Category.FOOD_AND_DINING);
        MERCHANT_MAP.put("barbeque nation",   Category.FOOD_AND_DINING);
        MERCHANT_MAP.put("barbeque",          Category.FOOD_AND_DINING);
        MERCHANT_MAP.put("wow momo",          Category.FOOD_AND_DINING);
        MERCHANT_MAP.put("wowmomo",           Category.FOOD_AND_DINING);
        MERCHANT_MAP.put("haldirams",         Category.FOOD_AND_DINING);
        MERCHANT_MAP.put("haldiram",          Category.FOOD_AND_DINING);
        MERCHANT_MAP.put("bikanervala",       Category.FOOD_AND_DINING);

        // Coffee / Tea chains
        MERCHANT_MAP.put("starbucks",         Category.FOOD_AND_DINING);
        MERCHANT_MAP.put("cafe coffee day",   Category.FOOD_AND_DINING);
        MERCHANT_MAP.put("chaayos",           Category.FOOD_AND_DINING);
        MERCHANT_MAP.put("chai point",        Category.FOOD_AND_DINING);
        MERCHANT_MAP.put("third wave",        Category.FOOD_AND_DINING);
        MERCHANT_MAP.put("blue tokai",        Category.FOOD_AND_DINING);
        MERCHANT_MAP.put("barista",           Category.FOOD_AND_DINING);
        MERCHANT_MAP.put("dunkin",            Category.FOOD_AND_DINING);

        // Cloud kitchens / delivery brands
        MERCHANT_MAP.put("faasos",            Category.FOOD_AND_DINING);
        MERCHANT_MAP.put("freshmenu",         Category.FOOD_AND_DINING);
        MERCHANT_MAP.put("box8",              Category.FOOD_AND_DINING);
        MERCHANT_MAP.put("behrouz",           Category.FOOD_AND_DINING);
        MERCHANT_MAP.put("eatfit",            Category.FOOD_AND_DINING);
        MERCHANT_MAP.put("biryani blues",     Category.FOOD_AND_DINING);
        MERCHANT_MAP.put("biryani by kilo",   Category.FOOD_AND_DINING);
        MERCHANT_MAP.put("licious",           Category.FOOD_AND_DINING);
        MERCHANT_MAP.put("freshtohome",       Category.FOOD_AND_DINING);
        MERCHANT_MAP.put("fresh to home",     Category.FOOD_AND_DINING);
        MERCHANT_MAP.put("fassos",            Category.FOOD_AND_DINING);
        MERCHANT_MAP.put("rebel foods",       Category.FOOD_AND_DINING);

        // Alcohol / liquor (keyword level)
        MERCHANT_MAP.put("wine shop",         Category.FOOD_AND_DINING);
        MERCHANT_MAP.put("liquor",            Category.FOOD_AND_DINING);
        MERCHANT_MAP.put("bar and",           Category.FOOD_AND_DINING);

        // Generic food keywords
        MERCHANT_MAP.put("restaurant",        Category.FOOD_AND_DINING);
        MERCHANT_MAP.put("dhaba",             Category.FOOD_AND_DINING);
        MERCHANT_MAP.put("bakery",            Category.FOOD_AND_DINING);
        MERCHANT_MAP.put("sweet shop",        Category.FOOD_AND_DINING);
        MERCHANT_MAP.put("canteen",           Category.FOOD_AND_DINING);
        MERCHANT_MAP.put("tiffin",            Category.FOOD_AND_DINING);
        MERCHANT_MAP.put("hotel food",        Category.FOOD_AND_DINING);

        // ─────────────────────────────────────────────────────────────────────
        // GROCERIES
        // ─────────────────────────────────────────────────────────────────────

        MERCHANT_MAP.put("blinkit",           Category.GROCERIES);
        MERCHANT_MAP.put("zepto",             Category.GROCERIES);
        MERCHANT_MAP.put("bigbasket",         Category.GROCERIES);
        MERCHANT_MAP.put("big basket",        Category.GROCERIES);
        MERCHANT_MAP.put("supr daily",        Category.GROCERIES);
        MERCHANT_MAP.put("suprdaily",         Category.GROCERIES);
        MERCHANT_MAP.put("dmart",             Category.GROCERIES);
        MERCHANT_MAP.put("d-mart",            Category.GROCERIES);
        MERCHANT_MAP.put("d mart",            Category.GROCERIES);
        MERCHANT_MAP.put("jiomart",           Category.GROCERIES);
        MERCHANT_MAP.put("jio mart",          Category.GROCERIES);
        MERCHANT_MAP.put("grofers",           Category.GROCERIES);
        MERCHANT_MAP.put("instamart",         Category.GROCERIES);
        MERCHANT_MAP.put("more supermarket",  Category.GROCERIES);
        MERCHANT_MAP.put("more retail",       Category.GROCERIES);
        MERCHANT_MAP.put("reliance fresh",    Category.GROCERIES);
        MERCHANT_MAP.put("reliance smart",    Category.GROCERIES);
        MERCHANT_MAP.put("smart bazaar",      Category.GROCERIES);
        MERCHANT_MAP.put("star bazaar",       Category.GROCERIES);
        MERCHANT_MAP.put("hypercity",         Category.GROCERIES);
        MERCHANT_MAP.put("big bazaar",        Category.GROCERIES);
        MERCHANT_MAP.put("bigbazaar",         Category.GROCERIES);
        MERCHANT_MAP.put("lulu hypermarket",  Category.GROCERIES);
        MERCHANT_MAP.put("spar",              Category.GROCERIES);
        MERCHANT_MAP.put("metro cash",        Category.GROCERIES);
        MERCHANT_MAP.put("nature basket",     Category.GROCERIES);
        MERCHANT_MAP.put("supermarket",       Category.GROCERIES);
        MERCHANT_MAP.put("kirana",            Category.GROCERIES);
        MERCHANT_MAP.put("sabzi",             Category.GROCERIES);
        MERCHANT_MAP.put("vegetable",         Category.GROCERIES);
        MERCHANT_MAP.put("grocery",           Category.GROCERIES);

        // ─────────────────────────────────────────────────────────────────────
        // SHOPPING
        // ─────────────────────────────────────────────────────────────────────

        // E-commerce
        MERCHANT_MAP.put("amazon",            Category.SHOPPING);
        MERCHANT_MAP.put("flipkart",          Category.SHOPPING);
        MERCHANT_MAP.put("myntra",            Category.SHOPPING);
        MERCHANT_MAP.put("ajio",              Category.SHOPPING);
        MERCHANT_MAP.put("nykaa",             Category.SHOPPING);
        MERCHANT_MAP.put("meesho",            Category.SHOPPING);
        MERCHANT_MAP.put("snapdeal",          Category.SHOPPING);
        MERCHANT_MAP.put("shopsy",            Category.SHOPPING);
        MERCHANT_MAP.put("tatacliq",          Category.SHOPPING);
        MERCHANT_MAP.put("tata cliq",         Category.SHOPPING);
        MERCHANT_MAP.put("lenskart",          Category.SHOPPING);
        MERCHANT_MAP.put("firstcry",          Category.SHOPPING);
        MERCHANT_MAP.put("first cry",         Category.SHOPPING);
        MERCHANT_MAP.put("bewakoof",          Category.SHOPPING);
        MERCHANT_MAP.put("purplle",           Category.SHOPPING);
        MERCHANT_MAP.put("mamaearth",         Category.SHOPPING);
        MERCHANT_MAP.put("plum goodness",     Category.SHOPPING);
        MERCHANT_MAP.put("mcaffeine",         Category.SHOPPING);
        MERCHANT_MAP.put("boat",              Category.SHOPPING);    // Boat earphones/speakers
        MERCHANT_MAP.put("noise",             Category.SHOPPING);

        // Fashion / apparel
        MERCHANT_MAP.put("h&m",               Category.SHOPPING);
        MERCHANT_MAP.put("zara",              Category.SHOPPING);
        MERCHANT_MAP.put("mango",             Category.SHOPPING);
        MERCHANT_MAP.put("marks spencer",     Category.SHOPPING);
        MERCHANT_MAP.put("marks and spencer", Category.SHOPPING);
        MERCHANT_MAP.put("fabindia",          Category.SHOPPING);
        MERCHANT_MAP.put("fab india",         Category.SHOPPING);
        MERCHANT_MAP.put("max fashion",       Category.SHOPPING);
        MERCHANT_MAP.put("westside",          Category.SHOPPING);
        MERCHANT_MAP.put("pantaloons",        Category.SHOPPING);
        MERCHANT_MAP.put("lifestyle",         Category.SHOPPING);
        MERCHANT_MAP.put("central mall",      Category.SHOPPING);
        MERCHANT_MAP.put("forever 21",        Category.SHOPPING);
        MERCHANT_MAP.put("only",              Category.SHOPPING);
        MERCHANT_MAP.put("vero moda",         Category.SHOPPING);
        MERCHANT_MAP.put("jack jones",        Category.SHOPPING);

        // Electronics
        MERCHANT_MAP.put("croma",             Category.SHOPPING);
        MERCHANT_MAP.put("reliance digital",  Category.SHOPPING);
        MERCHANT_MAP.put("vijay sales",       Category.SHOPPING);
        MERCHANT_MAP.put("decathlon",         Category.SHOPPING);
        MERCHANT_MAP.put("apple store",       Category.SHOPPING);
        MERCHANT_MAP.put("istore",            Category.SHOPPING);
        MERCHANT_MAP.put("samsung",           Category.SHOPPING);

        // Home / furniture
        MERCHANT_MAP.put("ikea",              Category.SHOPPING);
        MERCHANT_MAP.put("pepperfry",         Category.SHOPPING);
        MERCHANT_MAP.put("urban ladder",      Category.SHOPPING);
        MERCHANT_MAP.put("urbanladder",       Category.SHOPPING);
        MERCHANT_MAP.put("hometown",          Category.SHOPPING);
        MERCHANT_MAP.put("godrej interio",    Category.SHOPPING);
        MERCHANT_MAP.put("furlenco",          Category.SHOPPING);

        // ─────────────────────────────────────────────────────────────────────
        // TRANSPORT  (more specific entries first)
        // ─────────────────────────────────────────────────────────────────────

        // Cab aggregators
        MERCHANT_MAP.put("uber",              Category.TRANSPORT);
        MERCHANT_MAP.put("ola cabs",          Category.TRANSPORT);
        MERCHANT_MAP.put("ola",               Category.TRANSPORT);
        MERCHANT_MAP.put("rapido",            Category.TRANSPORT);
        MERCHANT_MAP.put("blusmart",          Category.TRANSPORT);
        MERCHANT_MAP.put("blu smart",         Category.TRANSPORT);
        MERCHANT_MAP.put("meru",              Category.TRANSPORT);
        MERCHANT_MAP.put("savaari",           Category.TRANSPORT);
        MERCHANT_MAP.put("zoomcar",           Category.TRANSPORT);
        MERCHANT_MAP.put("zoom car",          Category.TRANSPORT);
        MERCHANT_MAP.put("drivezy",           Category.TRANSPORT);
        MERCHANT_MAP.put("yulu",              Category.TRANSPORT);
        MERCHANT_MAP.put("bounce",            Category.TRANSPORT);
        MERCHANT_MAP.put("vogo",              Category.TRANSPORT);

        // Metro / bus
        MERCHANT_MAP.put("dmrc",              Category.TRANSPORT);
        MERCHANT_MAP.put("nmrc",              Category.TRANSPORT);
        MERCHANT_MAP.put("bmtc",              Category.TRANSPORT);
        MERCHANT_MAP.put("best bus",          Category.TRANSPORT);
        MERCHANT_MAP.put("metro recharge",    Category.TRANSPORT);
        MERCHANT_MAP.put("metro rail",        Category.TRANSPORT);
        MERCHANT_MAP.put("noida metro",       Category.TRANSPORT);

        // Fuel
        MERCHANT_MAP.put("fasttag",           Category.TRANSPORT);
        MERCHANT_MAP.put("fastag",            Category.TRANSPORT);
        MERCHANT_MAP.put("toll plaza",        Category.TRANSPORT);
        MERCHANT_MAP.put("toll tax",          Category.TRANSPORT);
        MERCHANT_MAP.put("petrol",            Category.TRANSPORT);
        MERCHANT_MAP.put("diesel",            Category.TRANSPORT);
        MERCHANT_MAP.put("fuel",              Category.TRANSPORT);
        MERCHANT_MAP.put("iocl",              Category.TRANSPORT);  // Indian Oil
        MERCHANT_MAP.put("indian oil",        Category.TRANSPORT);
        MERCHANT_MAP.put("bpcl",              Category.TRANSPORT);  // Bharat Petroleum
        MERCHANT_MAP.put("bharat petroleum",  Category.TRANSPORT);
        MERCHANT_MAP.put("bharat petro",      Category.TRANSPORT);
        MERCHANT_MAP.put("hpcl",              Category.TRANSPORT);  // Hindustan Petroleum
        MERCHANT_MAP.put("hp petrol",         Category.TRANSPORT);
        MERCHANT_MAP.put("shell",             Category.TRANSPORT);
        MERCHANT_MAP.put("essar oil",         Category.TRANSPORT);
        MERCHANT_MAP.put("parking",           Category.TRANSPORT);

        // Rail / bus tickets
        MERCHANT_MAP.put("irctc",             Category.TRANSPORT);
        MERCHANT_MAP.put("redbus",            Category.TRANSPORT);
        MERCHANT_MAP.put("abhibus",           Category.TRANSPORT);

        // ─────────────────────────────────────────────────────────────────────
        // TRAVEL  (specific entries before generic "paytm")
        // ─────────────────────────────────────────────────────────────────────

        MERCHANT_MAP.put("makemytrip",        Category.TRAVEL);
        MERCHANT_MAP.put("make my trip",      Category.TRAVEL);
        MERCHANT_MAP.put("mmt",               Category.TRAVEL);
        MERCHANT_MAP.put("goibibo",           Category.TRAVEL);
        MERCHANT_MAP.put("cleartrip",         Category.TRAVEL);
        MERCHANT_MAP.put("easemytrip",        Category.TRAVEL);
        MERCHANT_MAP.put("ease my trip",      Category.TRAVEL);
        MERCHANT_MAP.put("yatra",             Category.TRAVEL);
        MERCHANT_MAP.put("paytm travel",      Category.TRAVEL);
        MERCHANT_MAP.put("ixigo",             Category.TRAVEL);
        MERCHANT_MAP.put("confirmtkt",        Category.TRAVEL);
        MERCHANT_MAP.put("railyatri",         Category.TRAVEL);

        // Airlines
        MERCHANT_MAP.put("indigo",            Category.TRAVEL);
        MERCHANT_MAP.put("spicejet",          Category.TRAVEL);
        MERCHANT_MAP.put("air india",         Category.TRAVEL);
        MERCHANT_MAP.put("vistara",           Category.TRAVEL);
        MERCHANT_MAP.put("akasa",             Category.TRAVEL);
        MERCHANT_MAP.put("airasia",           Category.TRAVEL);
        MERCHANT_MAP.put("go first",          Category.TRAVEL);
        MERCHANT_MAP.put("gofirst",           Category.TRAVEL);

        // Hotels / stays
        MERCHANT_MAP.put("oyo",               Category.TRAVEL);
        MERCHANT_MAP.put("treebo",            Category.TRAVEL);
        MERCHANT_MAP.put("fabhotels",         Category.TRAVEL);
        MERCHANT_MAP.put("zostel",            Category.TRAVEL);
        MERCHANT_MAP.put("airbnb",            Category.TRAVEL);
        MERCHANT_MAP.put("mmt hotel",         Category.TRAVEL);

        // ─────────────────────────────────────────────────────────────────────
        // ENTERTAINMENT
        // ─────────────────────────────────────────────────────────────────────

        // Streaming video
        MERCHANT_MAP.put("netflix",           Category.ENTERTAINMENT);
        MERCHANT_MAP.put("hotstar",           Category.ENTERTAINMENT);
        MERCHANT_MAP.put("disney",            Category.ENTERTAINMENT);
        MERCHANT_MAP.put("prime video",       Category.ENTERTAINMENT);
        MERCHANT_MAP.put("primevideo",        Category.ENTERTAINMENT);
        MERCHANT_MAP.put("amazon prime",      Category.ENTERTAINMENT);
        MERCHANT_MAP.put("zee5",              Category.ENTERTAINMENT);
        MERCHANT_MAP.put("sonyliv",           Category.ENTERTAINMENT);
        MERCHANT_MAP.put("jiocinema",         Category.ENTERTAINMENT);
        MERCHANT_MAP.put("jio cinema",        Category.ENTERTAINMENT);
        MERCHANT_MAP.put("altbalaji",         Category.ENTERTAINMENT);
        MERCHANT_MAP.put("mxplayer",          Category.ENTERTAINMENT);
        MERCHANT_MAP.put("mx player",         Category.ENTERTAINMENT);
        MERCHANT_MAP.put("voot",              Category.ENTERTAINMENT);

        // Music
        MERCHANT_MAP.put("spotify",           Category.ENTERTAINMENT);
        MERCHANT_MAP.put("gaana",             Category.ENTERTAINMENT);
        MERCHANT_MAP.put("wynk",              Category.ENTERTAINMENT);
        MERCHANT_MAP.put("jiosaavn",          Category.ENTERTAINMENT);
        MERCHANT_MAP.put("jio saavn",         Category.ENTERTAINMENT);
        MERCHANT_MAP.put("hungama",           Category.ENTERTAINMENT);
        MERCHANT_MAP.put("apple music",       Category.ENTERTAINMENT);
        MERCHANT_MAP.put("youtube premium",   Category.ENTERTAINMENT);

        // Gaming
        MERCHANT_MAP.put("steam",             Category.ENTERTAINMENT);
        MERCHANT_MAP.put("playstation",       Category.ENTERTAINMENT);
        MERCHANT_MAP.put("xbox",              Category.ENTERTAINMENT);
        MERCHANT_MAP.put("junglee games",     Category.ENTERTAINMENT);
        MERCHANT_MAP.put("dream11",           Category.ENTERTAINMENT);
        MERCHANT_MAP.put("my11circle",        Category.ENTERTAINMENT);
        MERCHANT_MAP.put("winzo",             Category.ENTERTAINMENT);
        MERCHANT_MAP.put("mpl ",              Category.ENTERTAINMENT);  // trailing space avoids "mpl" in bank names

        // Ticketing
        MERCHANT_MAP.put("bookmyshow",        Category.ENTERTAINMENT);
        MERCHANT_MAP.put("book my show",      Category.ENTERTAINMENT);
        MERCHANT_MAP.put("paytm insider",     Category.ENTERTAINMENT);
        MERCHANT_MAP.put("insider.in",        Category.ENTERTAINMENT);

        // Payments
        MERCHANT_MAP.put("google play",       Category.ENTERTAINMENT);
        MERCHANT_MAP.put("playstore",         Category.ENTERTAINMENT);
        MERCHANT_MAP.put("apple",             Category.ENTERTAINMENT);  // App Store / iTunes
        MERCHANT_MAP.put("itunes",            Category.ENTERTAINMENT);

        // ─────────────────────────────────────────────────────────────────────
        // UTILITIES & BILLS
        // ─────────────────────────────────────────────────────────────────────

        // Telecom
        MERCHANT_MAP.put("airtel",            Category.UTILITIES_AND_BILLS);
        MERCHANT_MAP.put("jio recharge",      Category.UTILITIES_AND_BILLS);
        MERCHANT_MAP.put("jio prepaid",       Category.UTILITIES_AND_BILLS);
        MERCHANT_MAP.put("reliance jio",      Category.UTILITIES_AND_BILLS);
        MERCHANT_MAP.put("vodafone",          Category.UTILITIES_AND_BILLS);
        MERCHANT_MAP.put("vi recharge",       Category.UTILITIES_AND_BILLS);
        MERCHANT_MAP.put("bsnl",              Category.UTILITIES_AND_BILLS);
        MERCHANT_MAP.put("mtnl",              Category.UTILITIES_AND_BILLS);
        MERCHANT_MAP.put("tata docomo",       Category.UTILITIES_AND_BILLS);

        // Internet / Broadband
        MERCHANT_MAP.put("act fibernet",      Category.UTILITIES_AND_BILLS);
        MERCHANT_MAP.put("hathway",           Category.UTILITIES_AND_BILLS);
        MERCHANT_MAP.put("den network",       Category.UTILITIES_AND_BILLS);
        MERCHANT_MAP.put("sify",              Category.UTILITIES_AND_BILLS);
        MERCHANT_MAP.put("tikona",            Category.UTILITIES_AND_BILLS);
        MERCHANT_MAP.put("you broadband",     Category.UTILITIES_AND_BILLS);
        MERCHANT_MAP.put("excitel",           Category.UTILITIES_AND_BILLS);
        MERCHANT_MAP.put("asianet",           Category.UTILITIES_AND_BILLS);
        MERCHANT_MAP.put("jio fiber",         Category.UTILITIES_AND_BILLS);
        MERCHANT_MAP.put("airtel fiber",      Category.UTILITIES_AND_BILLS);

        // Electricity boards
        MERCHANT_MAP.put("bescom",            Category.UTILITIES_AND_BILLS);
        MERCHANT_MAP.put("msedcl",            Category.UTILITIES_AND_BILLS);
        MERCHANT_MAP.put("tneb",              Category.UTILITIES_AND_BILLS);
        MERCHANT_MAP.put("bses",              Category.UTILITIES_AND_BILLS);
        MERCHANT_MAP.put("cesc",              Category.UTILITIES_AND_BILLS);
        MERCHANT_MAP.put("torrent power",     Category.UTILITIES_AND_BILLS);
        MERCHANT_MAP.put("adani electricity", Category.UTILITIES_AND_BILLS);
        MERCHANT_MAP.put("tata power",        Category.UTILITIES_AND_BILLS);
        MERCHANT_MAP.put("wbsedcl",           Category.UTILITIES_AND_BILLS);
        MERCHANT_MAP.put("kseb",              Category.UTILITIES_AND_BILLS);  // Kerala
        MERCHANT_MAP.put("pspcl",             Category.UTILITIES_AND_BILLS);  // Punjab
        MERCHANT_MAP.put("uppcl",             Category.UTILITIES_AND_BILLS);  // UP
        MERCHANT_MAP.put("electricity bill",  Category.UTILITIES_AND_BILLS);
        MERCHANT_MAP.put("electricity",       Category.UTILITIES_AND_BILLS);

        // Gas
        MERCHANT_MAP.put("indraprastha gas",  Category.UTILITIES_AND_BILLS);
        MERCHANT_MAP.put("mahanagar gas",     Category.UTILITIES_AND_BILLS);
        MERCHANT_MAP.put("adani gas",         Category.UTILITIES_AND_BILLS);
        MERCHANT_MAP.put("gail gas",          Category.UTILITIES_AND_BILLS);
        MERCHANT_MAP.put("gujarat gas",       Category.UTILITIES_AND_BILLS);
        MERCHANT_MAP.put("gas bill",          Category.UTILITIES_AND_BILLS);

        // DTH
        MERCHANT_MAP.put("tata play",         Category.UTILITIES_AND_BILLS);
        MERCHANT_MAP.put("tataplay",          Category.UTILITIES_AND_BILLS);
        MERCHANT_MAP.put("tata sky",          Category.UTILITIES_AND_BILLS);
        MERCHANT_MAP.put("tatasky",           Category.UTILITIES_AND_BILLS);
        MERCHANT_MAP.put("dish tv",           Category.UTILITIES_AND_BILLS);
        MERCHANT_MAP.put("dishtv",            Category.UTILITIES_AND_BILLS);
        MERCHANT_MAP.put("d2h",               Category.UTILITIES_AND_BILLS);
        MERCHANT_MAP.put("airtel digital",    Category.UTILITIES_AND_BILLS);
        MERCHANT_MAP.put("sun direct",        Category.UTILITIES_AND_BILLS);

        // Water / municipal
        MERCHANT_MAP.put("water bill",        Category.UTILITIES_AND_BILLS);
        MERCHANT_MAP.put("municipal",         Category.UTILITIES_AND_BILLS);
        MERCHANT_MAP.put("bbmp",              Category.UTILITIES_AND_BILLS);
        MERCHANT_MAP.put("mcgm",              Category.UTILITIES_AND_BILLS);
        MERCHANT_MAP.put("dmc",               Category.UTILITIES_AND_BILLS);

        // Hosting / cloud
        MERCHANT_MAP.put("godaddy",           Category.UTILITIES_AND_BILLS);
        MERCHANT_MAP.put("namecheap",         Category.UTILITIES_AND_BILLS);
        MERCHANT_MAP.put("aws",               Category.UTILITIES_AND_BILLS);
        MERCHANT_MAP.put("digitalocean",      Category.UTILITIES_AND_BILLS);
        MERCHANT_MAP.put("cloudflare",        Category.UTILITIES_AND_BILLS);
        MERCHANT_MAP.put("hostinger",         Category.UTILITIES_AND_BILLS);
        MERCHANT_MAP.put("bigrock",           Category.UTILITIES_AND_BILLS);

        // Bill payments (platform-level)
        MERCHANT_MAP.put("billdesk",          Category.UTILITIES_AND_BILLS);
        MERCHANT_MAP.put("bbps",              Category.UTILITIES_AND_BILLS);
        MERCHANT_MAP.put("recharge",          Category.UTILITIES_AND_BILLS);

        // ─────────────────────────────────────────────────────────────────────
        // RENT & HOUSING
        // ─────────────────────────────────────────────────────────────────────

        MERCHANT_MAP.put("nobroker",          Category.RENT_AND_HOUSING);
        MERCHANT_MAP.put("no broker",         Category.RENT_AND_HOUSING);
        MERCHANT_MAP.put("nestaway",          Category.RENT_AND_HOUSING);
        MERCHANT_MAP.put("stazy",             Category.RENT_AND_HOUSING);
        MERCHANT_MAP.put("colive",            Category.RENT_AND_HOUSING);
        MERCHANT_MAP.put("co-live",           Category.RENT_AND_HOUSING);
        MERCHANT_MAP.put("oyo life",          Category.RENT_AND_HOUSING);
        MERCHANT_MAP.put("house rent",        Category.RENT_AND_HOUSING);
        MERCHANT_MAP.put("flat rent",         Category.RENT_AND_HOUSING);
        MERCHANT_MAP.put("room rent",         Category.RENT_AND_HOUSING);
        MERCHANT_MAP.put("pg rent",           Category.RENT_AND_HOUSING);
        MERCHANT_MAP.put("hostel fee",        Category.RENT_AND_HOUSING);
        MERCHANT_MAP.put("maintenance chrg",  Category.RENT_AND_HOUSING);
        MERCHANT_MAP.put("society fee",       Category.RENT_AND_HOUSING);
        MERCHANT_MAP.put("society maint",     Category.RENT_AND_HOUSING);
        MERCHANT_MAP.put("apartment maint",   Category.RENT_AND_HOUSING);
        MERCHANT_MAP.put("rent payment",      Category.RENT_AND_HOUSING);

        // ─────────────────────────────────────────────────────────────────────
        // HEALTH & FITNESS
        // ─────────────────────────────────────────────────────────────────────

        // Online pharmacy
        MERCHANT_MAP.put("pharmeasy",         Category.HEALTH_AND_FITNESS);
        MERCHANT_MAP.put("netmeds",           Category.HEALTH_AND_FITNESS);
        MERCHANT_MAP.put("1mg",               Category.HEALTH_AND_FITNESS);
        MERCHANT_MAP.put("tata 1mg",          Category.HEALTH_AND_FITNESS);
        MERCHANT_MAP.put("tata1mg",           Category.HEALTH_AND_FITNESS);
        MERCHANT_MAP.put("medkart",           Category.HEALTH_AND_FITNESS);

        // Pharmacy chains
        MERCHANT_MAP.put("apollo pharmacy",   Category.HEALTH_AND_FITNESS);
        MERCHANT_MAP.put("medplus",           Category.HEALTH_AND_FITNESS);
        MERCHANT_MAP.put("frank ross",        Category.HEALTH_AND_FITNESS);
        MERCHANT_MAP.put("guardian pharmacy", Category.HEALTH_AND_FITNESS);
        MERCHANT_MAP.put("wellness forever",  Category.HEALTH_AND_FITNESS);
        MERCHANT_MAP.put("aster pharmacy",    Category.HEALTH_AND_FITNESS);
        MERCHANT_MAP.put("pharmacy",          Category.HEALTH_AND_FITNESS);
        MERCHANT_MAP.put("medical store",     Category.HEALTH_AND_FITNESS);
        MERCHANT_MAP.put("chemist",           Category.HEALTH_AND_FITNESS);
        MERCHANT_MAP.put("medicine",          Category.HEALTH_AND_FITNESS);

        // Hospitals / clinics
        MERCHANT_MAP.put("apollo hospital",   Category.HEALTH_AND_FITNESS);
        MERCHANT_MAP.put("fortis",            Category.HEALTH_AND_FITNESS);
        MERCHANT_MAP.put("max hospital",      Category.HEALTH_AND_FITNESS);
        MERCHANT_MAP.put("medanta",           Category.HEALTH_AND_FITNESS);
        MERCHANT_MAP.put("manipal hospital",  Category.HEALTH_AND_FITNESS);
        MERCHANT_MAP.put("narayana health",   Category.HEALTH_AND_FITNESS);
        MERCHANT_MAP.put("hospital",          Category.HEALTH_AND_FITNESS);
        MERCHANT_MAP.put("clinic",            Category.HEALTH_AND_FITNESS);
        MERCHANT_MAP.put("diagnostic",        Category.HEALTH_AND_FITNESS);

        // Labs
        MERCHANT_MAP.put("dr lal",            Category.HEALTH_AND_FITNESS);
        MERCHANT_MAP.put("srl",               Category.HEALTH_AND_FITNESS);
        MERCHANT_MAP.put("thyrocare",         Category.HEALTH_AND_FITNESS);
        MERCHANT_MAP.put("healthians",        Category.HEALTH_AND_FITNESS);
        MERCHANT_MAP.put("metropolis lab",    Category.HEALTH_AND_FITNESS);
        MERCHANT_MAP.put("pathology",         Category.HEALTH_AND_FITNESS);

        // Fitness / wellness
        MERCHANT_MAP.put("cult.fit",          Category.HEALTH_AND_FITNESS);
        MERCHANT_MAP.put("cultfit",           Category.HEALTH_AND_FITNESS);
        MERCHANT_MAP.put("curefit",           Category.HEALTH_AND_FITNESS);
        MERCHANT_MAP.put("healthifyme",       Category.HEALTH_AND_FITNESS);
        MERCHANT_MAP.put("practo",            Category.HEALTH_AND_FITNESS);
        MERCHANT_MAP.put("tata health",       Category.HEALTH_AND_FITNESS);
        MERCHANT_MAP.put("mfine",             Category.HEALTH_AND_FITNESS);
        MERCHANT_MAP.put("anytime fitness",   Category.HEALTH_AND_FITNESS);
        MERCHANT_MAP.put("gold's gym",        Category.HEALTH_AND_FITNESS);
        MERCHANT_MAP.put("golds gym",         Category.HEALTH_AND_FITNESS);
        MERCHANT_MAP.put("gym",               Category.HEALTH_AND_FITNESS);
        MERCHANT_MAP.put("fitness",           Category.HEALTH_AND_FITNESS);
        MERCHANT_MAP.put("yoga",              Category.HEALTH_AND_FITNESS);

        // ─────────────────────────────────────────────────────────────────────
        // LOAN & EMI
        // ─────────────────────────────────────────────────────────────────────

        // Fintech lenders
        MERCHANT_MAP.put("kreditbee",         Category.LOAN_AND_EMI);
        MERCHANT_MAP.put("lazypay",           Category.LOAN_AND_EMI);
        MERCHANT_MAP.put("paysense",          Category.LOAN_AND_EMI);
        MERCHANT_MAP.put("cashe",             Category.LOAN_AND_EMI);
        MERCHANT_MAP.put("moneytap",          Category.LOAN_AND_EMI);
        MERCHANT_MAP.put("money tap",         Category.LOAN_AND_EMI);
        MERCHANT_MAP.put("moneyview",         Category.LOAN_AND_EMI);
        MERCHANT_MAP.put("navi",              Category.LOAN_AND_EMI);
        MERCHANT_MAP.put("simpl",             Category.LOAN_AND_EMI);
        MERCHANT_MAP.put("slice",             Category.LOAN_AND_EMI);
        MERCHANT_MAP.put("uni card",          Category.LOAN_AND_EMI);
        MERCHANT_MAP.put("unicard",           Category.LOAN_AND_EMI);
        MERCHANT_MAP.put("axio",              Category.LOAN_AND_EMI);
        MERCHANT_MAP.put("incred",            Category.LOAN_AND_EMI);
        MERCHANT_MAP.put("fibe",              Category.LOAN_AND_EMI);
        MERCHANT_MAP.put("prefr",             Category.LOAN_AND_EMI);

        // NBFC / banks for loans
        MERCHANT_MAP.put("bajaj finserv",     Category.LOAN_AND_EMI);
        MERCHANT_MAP.put("bajaj finance",     Category.LOAN_AND_EMI);
        MERCHANT_MAP.put("hdfc loan",         Category.LOAN_AND_EMI);
        MERCHANT_MAP.put("icici loan",        Category.LOAN_AND_EMI);
        MERCHANT_MAP.put("sbi loan",          Category.LOAN_AND_EMI);
        MERCHANT_MAP.put("axis loan",         Category.LOAN_AND_EMI);
        MERCHANT_MAP.put("home loan",         Category.LOAN_AND_EMI);
        MERCHANT_MAP.put("car loan",          Category.LOAN_AND_EMI);
        MERCHANT_MAP.put("personal loan",     Category.LOAN_AND_EMI);
        MERCHANT_MAP.put("emi payment",       Category.LOAN_AND_EMI);
        MERCHANT_MAP.put("loan emi",          Category.LOAN_AND_EMI);
        MERCHANT_MAP.put("loan repay",        Category.LOAN_AND_EMI);
        MERCHANT_MAP.put("hdfc credila",      Category.LOAN_AND_EMI);
        MERCHANT_MAP.put("education loan",    Category.LOAN_AND_EMI);

        // Credit card payments
        MERCHANT_MAP.put("credit card bill",  Category.LOAN_AND_EMI);
        MERCHANT_MAP.put("cc bill",           Category.LOAN_AND_EMI);
        MERCHANT_MAP.put("cc payment",        Category.LOAN_AND_EMI);
        MERCHANT_MAP.put("card payment",      Category.LOAN_AND_EMI);

        // ─────────────────────────────────────────────────────────────────────
        // INVESTMENT
        // ─────────────────────────────────────────────────────────────────────

        // Brokers / trading
        MERCHANT_MAP.put("zerodha",           Category.INVESTMENT);
        MERCHANT_MAP.put("groww",             Category.INVESTMENT);
        MERCHANT_MAP.put("upstox",            Category.INVESTMENT);
        MERCHANT_MAP.put("angel broking",     Category.INVESTMENT);
        MERCHANT_MAP.put("angel one",         Category.INVESTMENT);
        MERCHANT_MAP.put("angelone",          Category.INVESTMENT);
        MERCHANT_MAP.put("icicidirect",       Category.INVESTMENT);
        MERCHANT_MAP.put("icici direct",      Category.INVESTMENT);
        MERCHANT_MAP.put("hdfc securities",   Category.INVESTMENT);
        MERCHANT_MAP.put("sharekhan",         Category.INVESTMENT);
        MERCHANT_MAP.put("motilal oswal",     Category.INVESTMENT);
        MERCHANT_MAP.put("kotak securities",  Category.INVESTMENT);
        MERCHANT_MAP.put("5paisa",            Category.INVESTMENT);
        MERCHANT_MAP.put("fyers",             Category.INVESTMENT);
        MERCHANT_MAP.put("dhan",              Category.INVESTMENT);

        // MF platforms
        MERCHANT_MAP.put("kuvera",            Category.INVESTMENT);
        MERCHANT_MAP.put("smallcase",         Category.INVESTMENT);
        MERCHANT_MAP.put("coin by zerodha",   Category.INVESTMENT);
        MERCHANT_MAP.put("paytm money",       Category.INVESTMENT);
        MERCHANT_MAP.put("etmoney",           Category.INVESTMENT);
        MERCHANT_MAP.put("bse star",          Category.INVESTMENT);
        MERCHANT_MAP.put("nse mf",            Category.INVESTMENT);

        // AMC payments
        MERCHANT_MAP.put("hdfc amc",          Category.INVESTMENT);
        MERCHANT_MAP.put("sbi amc",           Category.INVESTMENT);
        MERCHANT_MAP.put("mirae asset",       Category.INVESTMENT);
        MERCHANT_MAP.put("axis amc",          Category.INVESTMENT);
        MERCHANT_MAP.put("parag parikh",      Category.INVESTMENT);
        MERCHANT_MAP.put("mutual fund",       Category.INVESTMENT);

        // Insurance
        MERCHANT_MAP.put("lic",               Category.INVESTMENT);
        MERCHANT_MAP.put("hdfc life",         Category.INVESTMENT);
        MERCHANT_MAP.put("icici pru",         Category.INVESTMENT);
        MERCHANT_MAP.put("sbi life",          Category.INVESTMENT);
        MERCHANT_MAP.put("max life",          Category.INVESTMENT);
        MERCHANT_MAP.put("bajaj allianz",     Category.INVESTMENT);
        MERCHANT_MAP.put("tata aia",          Category.INVESTMENT);
        MERCHANT_MAP.put("insurance",         Category.INVESTMENT);

        // Fixed / recurring deposits, PPF
        MERCHANT_MAP.put("sip",               Category.INVESTMENT);
        MERCHANT_MAP.put("fd booking",        Category.INVESTMENT);
        MERCHANT_MAP.put("rd installment",    Category.INVESTMENT);
        MERCHANT_MAP.put("ppf",               Category.INVESTMENT);
        MERCHANT_MAP.put("nps",               Category.INVESTMENT);

        // ─────────────────────────────────────────────────────────────────────
        // EDUCATION  — map to OTHER for now (no dedicated category)
        // ─────────────────────────────────────────────────────────────────────
        // These are tagged OTHER intentionally until an EDUCATION category is added.
        // Keeping them here makes them easy to promote later.

        // ─────────────────────────────────────────────────────────────────────
        // TRANSFERS — catch common peer payment apps so they aren't sent to AI
        // ─────────────────────────────────────────────────────────────────────

        MERCHANT_MAP.put("phonepe",           Category.TRANSFERS);
        // "paytm" intentionally omitted — it appears in merchant UPI handles (e.g. shop@paytm)
        // and would misclassify local merchant payments as TRANSFERS.
        // "paytm money" (INVESTMENT) and "paytm travel" (TRAVEL) above are specific enough.
        MERCHANT_MAP.put("gpay",              Category.TRANSFERS);
        MERCHANT_MAP.put("google pay",        Category.TRANSFERS);
        MERCHANT_MAP.put("bhim",              Category.TRANSFERS);
        // "cred" intentionally omitted — it substring-matches "credited" in NEFT narrations.
        MERCHANT_MAP.put("razorpay",          Category.TRANSFERS);
        MERCHANT_MAP.put("transfer to",       Category.TRANSFERS);
        MERCHANT_MAP.put("sent to",           Category.TRANSFERS);
        MERCHANT_MAP.put("payment to",        Category.TRANSFERS);
        MERCHANT_MAP.put("pay to",            Category.TRANSFERS);
    }

    /**
     * Scan (counterpartyName + merchantName + rawNarration) as a lowercase haystack.
     * counterpartyName is checked first because it is the cleaned, human-readable
     * name extracted by the parser — more reliable than raw narration text.
     * Returns the first registered keyword that appears in it (insertion order).
     */
    public Optional<Category> lookup(String merchantName, String rawNarration, String counterpartyName) {
        String haystack = buildHaystack(merchantName, rawNarration, counterpartyName);
        for (Map.Entry<String, Category> entry : MERCHANT_MAP.entrySet()) {
            if (haystack.contains(entry.getKey())) {
                return Optional.of(entry.getValue());
            }
        }
        return Optional.empty();
    }

    private String buildHaystack(String merchantName, String rawNarration, String counterpartyName) {
        StringBuilder sb = new StringBuilder();
        if (counterpartyName != null) sb.append(counterpartyName.toLowerCase()).append(" ");
        if (merchantName     != null) sb.append(merchantName.toLowerCase()).append(" ");
        if (rawNarration     != null) sb.append(rawNarration.toLowerCase());
        return sb.toString();
    }
}
