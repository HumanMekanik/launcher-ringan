package id.oprek.tvlauncher;

/**
 * Waktu sholat untuk Yogyakarta (WIB), dihitung offline tanpa internet.
 *
 * Kriteria mengikuti jadwal Majelis Tarjih dan Tajdid PP Muhammadiyah untuk Yogyakarta
 * (lintang -07 47' 48", bujur 110 21' 50" BT), dan parameternya dikalibrasi terhadap
 * tabel imsakiyah 1447 H (18 Feb - 19 Mar 2026) yang mereka terbitkan:
 *
 *  - Subuh : tinggi matahari -18 derajat
 *  - Zuhur : tengah hari (transit) matahari
 *  - Asar  : panjang bayangan = panjang benda + bayangan saat tengah hari
 *  - Magrib: tinggi matahari -1,19 derajat (sudah memperhitungkan refraksi dan ketinggian tempat)
 *  - Isya  : tinggi matahari -18 derajat
 *  - Ihtiyat 1 menit, hasil dibulatkan ke menit berikutnya
 *
 * Kelas ini murni Java (tanpa Android) supaya mudah diuji.
 */
public final class PrayerTimes {

    public static final double LATITUDE = -(7 + 47 / 60.0 + 48 / 3600.0);
    public static final double LONGITUDE = 110 + 21 / 60.0 + 50 / 3600.0;
    public static final double TIMEZONE = 7.0; // WIB

    public static final int SUBUH = 0;
    public static final int ZUHUR = 1;
    public static final int ASAR = 2;
    public static final int MAGRIB = 3;
    public static final int ISYA = 4;

    private static final double FAJR_ALTITUDE = -18.0;
    private static final double ISHA_ALTITUDE = -18.0;
    private static final double MAGHRIB_ALTITUDE = -1.19;
    private static final double IHTIYAT_MINUTES = 1.0;

    private PrayerTimes() {
    }

    /**
     * @return menit sejak tengah malam (waktu WIB) untuk
     * {Subuh, Zuhur, Asar, Magrib, Isya} pada tanggal tersebut.
     */
    public static int[] compute(int year, int month, int day) {
        double[] exact = exactMinutes(year, month, day);
        int[] result = new int[5];
        for (int i = 0; i < 5; i++) {
            result[i] = (int) Math.ceil(exact[i] + IHTIYAT_MINUTES - 1e-9);
        }
        return result;
    }

    /** Waktu persis (menit sejak tengah malam, pecahan) sebelum ihtiyat dan pembulatan. */
    public static double[] exactMinutes(int year, int month, int day) {
        double[] noon = sunAt(year, month, day, 12.0);
        double zuhur = 720.0 - 4.0 * LONGITUDE - noon[1] + TIMEZONE * 60.0;

        double[] r = new double[5];
        r[SUBUH] = eventMinutes(year, month, day, FAJR_ALTITUDE, -1, false, 3);
        r[ZUHUR] = zuhur;
        r[ASAR] = eventMinutes(year, month, day, 0.0, +1, true, 1);
        r[MAGRIB] = eventMinutes(year, month, day, MAGHRIB_ALTITUDE, +1, false, 3);
        r[ISYA] = eventMinutes(year, month, day, ISHA_ALTITUDE, +1, false, 3);
        return r;
    }

    /**
     * Menit lokal saat matahari mencapai tinggi tertentu.
     *
     * @param sign -1 untuk sebelum tengah hari, +1 untuk sesudahnya
     * @param asr  true: pakai tinggi matahari untuk Asar (bayangan = benda + bayangan tengah hari)
     */
    private static double eventMinutes(int year, int month, int day, double altitude,
                                       int sign, boolean asr, int iterations) {
        double t = 720.0;
        double phi = Math.toRadians(LATITUDE);
        for (int i = 0; i < iterations; i++) {
            double[] sun = sunAt(year, month, day, t / 60.0);
            double transit = 720.0 - 4.0 * LONGITUDE - sun[1] + TIMEZONE * 60.0;
            double delta = Math.toRadians(sun[0]);
            double alt = altitude;
            if (asr) {
                alt = Math.toDegrees(Math.atan(1.0 / (1.0 + Math.tan(Math.abs(phi - delta)))));
            }
            double c = (Math.sin(Math.toRadians(alt)) - Math.sin(phi) * Math.sin(delta))
                    / (Math.cos(phi) * Math.cos(delta));
            c = Math.max(-1.0, Math.min(1.0, c));
            t = transit + sign * Math.toDegrees(Math.acos(c)) * 4.0;
        }
        return t;
    }

    /**
     * Posisi matahari (algoritma NOAA).
     *
     * @return {deklinasi dalam derajat, equation of time dalam menit}
     */
    private static double[] sunAt(int year, int month, int day, double hourLocal) {
        int a = (14 - month) / 12;
        int yy = year + 4800 - a;
        int mm = month + 12 * a - 3;
        int jdn = day + (153 * mm + 2) / 5 + 365 * yy + yy / 4 - yy / 100 + yy / 400 - 32045;
        double jd = jdn - 0.5 + (hourLocal - TIMEZONE) / 24.0;
        double jc = (jd - 2451545.0) / 36525.0;

        double l0 = (280.46646 + jc * (36000.76983 + jc * 0.0003032)) % 360.0;
        if (l0 < 0) {
            l0 += 360.0;
        }
        double m = Math.toRadians(357.52911 + jc * (35999.05029 - 0.0001537 * jc));
        double e = 0.016708634 - jc * (0.000042037 + 0.0000001267 * jc);
        double c = Math.sin(m) * (1.914602 - jc * (0.004817 + 0.000014 * jc))
                + Math.sin(2 * m) * (0.019993 - 0.000101 * jc)
                + Math.sin(3 * m) * 0.000289;
        double om = Math.toRadians(125.04 - 1934.136 * jc);
        double apparent = l0 + c - 0.00569 - 0.00478 * Math.sin(om);
        double obliquity = 23.0 + (26.0 + (21.448 - jc * (46.815 + jc * (0.00059 - jc * 0.001813)))
                / 60.0) / 60.0 + 0.00256 * Math.cos(om);

        double declination = Math.toDegrees(Math.asin(
                Math.sin(Math.toRadians(obliquity)) * Math.sin(Math.toRadians(apparent))));

        double y = Math.tan(Math.toRadians(obliquity / 2.0));
        y = y * y;
        double l0r = Math.toRadians(l0);
        double eot = 4.0 * Math.toDegrees(
                y * Math.sin(2 * l0r)
                        - 2 * e * Math.sin(m)
                        + 4 * e * y * Math.sin(m) * Math.cos(2 * l0r)
                        - 0.5 * y * y * Math.sin(4 * l0r)
                        - 1.25 * e * e * Math.sin(2 * m));

        return new double[]{declination, eot};
    }
}
