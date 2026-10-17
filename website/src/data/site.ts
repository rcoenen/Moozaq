/** GitHub release URLs go here later. */
export const downloads = {
  android: "#",
  mac: "#",
};

export const site = {
  url: "https://moozaq.com",
  title: "Moozaq — Set the mood. Let it play.",
  description:
    "Free and open source. No ads, no accounts, and no subscriptions. Long-form music mixes for whatever you're getting into, played on your home speakers.",
  image: "/og.png",
};

export type Mix = {
  id: string;
  title: string;
  label: string;
  creator: string;
  duration: string;
};

export type Mood = {
  id: string;
  name: string;
  line: string;
  mixes: Mix[];
};

const mix = (
  id: string,
  title: string,
  label: string,
  creator: string,
  duration: string,
): Mix => ({ id, title, label, creator, duration });

export const art = (id: string) => `/artwork/${id}.jpg`;

export const moods: Mood[] = [
  {
    id: "focus",
    name: "Find your focus",
    line: "Music for getting into the zone.",
    mixes: [
      mix(
        "dKUL1WnIG-Y",
        "RENDEZVOUS AT LUNAR BASE 7 (Vol. 7) | 1960s Space Age Pop & Sci-Fi Lounge | Retro Deep Focus",
        "Rendezvous at Lunar Base 7",
        "The Safehouse Lounge",
        "1 h 30 min",
      ),
      mix(
        "4ZGkurkaePg",
        "THE PRAGUE CIPHER | 1960s Iron Curtain Spy Jazz | Noir Focus Music for Deep Work",
        "The Prague Cipher",
        "The Safehouse Lounge",
        "1 h 24 min",
      ),
      mix(
        "s18iBl69S1o",
        "the palm springs sound | sophisticated jazz for focus",
        "The palm springs sound",
        "Chill Palace",
        "61 min",
      ),
    ],
  },
  {
    id: "morning",
    name: "Ease into the day",
    line: "Warm sounds. No rush.",
    mixes: [
      mix(
        "PVXQeJM-bNQ",
        "Just Being Myself. – Warm, Cozy Vintage Jazz for Autumn Relaxation",
        "Just Being Myself.",
        "Sepia Lounge",
        "3 h 33 min",
      ),
      mix(
        "7WWEJWVlVps",
        "No Plans. Just Coffee. – Vintage Noir Jazz for Relaxation, Work & Reading",
        "No Plans. Just Coffee.",
        "Sepia Lounge",
        "2 h 11 min",
      ),
      mix(
        "45mMlpIu5GM",
        "Coffee First. Always. – Vintage Noir Jazz for Work, Reading & Relaxation",
        "Coffee First. Always.",
        "Sepia Lounge",
        "3 h 24 min",
      ),
    ],
  },
  {
    id: "cooking",
    name: "Something's cooking",
    line: "Give the kitchen its own rhythm.",
    mixes: [
      mix(
        "itSHCwtXico",
        "HAVANA NOIR ✦ 70s Cuban Crime Jazz & Mafia Espionage [2 Hours Mix]",
        "Havana Noir",
        "Afterhours Club '70",
        "2 h",
      ),
      mix(
        "vcAflQW9v7w",
        "DARK NAPLES ✦ 70s Italian Crime Jazz & Cinematic Funk",
        "Dark Naples",
        "Afterhours Club '70",
        "2 h",
      ),
      mix(
        "BLEdysTwz3E",
        "THE AMALFI COAST SOUND 🍋🌊 [90 MINS] 1962 Mediterranean Luxury & Villa Jazz",
        "The Amalfi Coast Sound",
        "Chill Palace",
        "1 h 31 min",
      ),
    ],
  },
  {
    id: "guests",
    name: "Have people over",
    line: "A room with company, and a sound to match.",
    mixes: [
      mix(
        "Fkq2A_NvINs",
        "DISTANT SIGNAL | Space Age Spy Surf & Mysterious Radio Lounge",
        "Distant Signal",
        "Noir Tiki Lounge",
        "1 h 22 min",
      ),
      mix(
        "nDduT_wC08o",
        "THE LAST RUN TO ELKO | Spaghetti Western Surf, Desert Exotica & Cold War Noir",
        "The Last Run to Elko",
        "Noir Tiki Lounge",
        "1 h 22 min",
      ),
      mix(
        "i30BcD0yxOQ",
        "NEON MIRAGE AT THE SANDS | 1960s Casino Noir Jazz | Late Night Desert Drive & Deep Focus",
        "Neon Mirage at the Sands",
        "The Safehouse Lounge",
        "1 h 11 min",
      ),
    ],
  },
  {
    id: "late",
    name: "Stay up a little longer",
    line: "For the hour when the day is over but you are not.",
    mixes: [
      mix(
        "Xdho5SVxEVI",
        "Dark Jazz & Cinematic Trip-Hop // Midnight Fever Motel // Dawn Is Still Hours Away",
        "Midnight Fever Motel",
        "Jazz Haze",
        "1 h 32 min",
      ),
      mix(
        "nO1BgJFMT2o",
        "Dark Jazz & Cinematic Trip-Hop // Golden Vice Club // The Night Begins",
        "Golden Vice Club",
        "Jazz Haze",
        "1 h 35 min",
      ),
      mix(
        "uU7xhv-bdl8",
        "Dark Jazz & Cinematic Trip-Hop // Red Halo Motel // 3 AM on an Endless Highway",
        "Red Halo Motel",
        "Jazz Haze",
        "1 h 35 min",
      ),
    ],
  },
  {
    id: "drift",
    name: "Drift somewhere else",
    line: "For when you want the room to take you somewhere.",
    mixes: [
      mix(
        "r6gcU96S-dc",
        "Altered Streets — Psychedelic Reggae Dub Flow",
        "Altered Streets",
        "Futuristic Sound",
        "1 h 31 min",
      ),
      mix(
        "ZwJXMB5Kkn4",
        "Harlequin Dreams 🎭 | Surreal Psychedelic Dub",
        "Harlequin Dreams",
        "Futuristic Sound",
        "1 h 25 min",
      ),
      mix(
        "STWE18yL5PU",
        "Abandoned Spaceship Dub | Psychedelic Reggae Rock",
        "Abandoned Spaceship Dub",
        "Futuristic Sound",
        "1 h 21 min",
      ),
    ],
  },
];

export const heroMixes = [
  moods[1].mixes[0],
  moods[4].mixes[0],
  moods[3].mixes[0],
];

export const longMixes = [
  moods[1].mixes[0],
  moods[4].mixes[0],
  moods[3].mixes[0],
];
