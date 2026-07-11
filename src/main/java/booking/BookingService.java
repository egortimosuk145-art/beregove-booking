package booking;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class BookingService {

    private final BookingRepository repository;
    private final TelegramService telegramService;

    public BookingService(BookingRepository repository, TelegramService telegramService) {
        this.repository = repository;
        this.telegramService = telegramService;
    }

    /**
     * Повертає зайняті дати по кожній послузі, наприклад:
     * { "forest": ["2026-07-18", "2026-07-19"], "sunset": [...], ... }
     * Саме в такому форматі це очікує фронтенд (об'єкт LIVE_UNAVAILABLE).
     */
    public Map<String, List<String>> getAvailability() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Booking b : repository.findAll()) {
            result.computeIfAbsent(b.getService(), k -> new ArrayList<>())
                  .addAll(expandDates(b.getDate(), b.getNights()));
        }
        return result;
    }

    /** Розгортає дату заїзду + кількість ночей у список конкретних зайнятих днів. */
    private List<String> expandDates(String startDate, Integer nights) {
        List<String> dates = new ArrayList<>();
        int n = (nights == null || nights < 1) ? 1 : nights;
        LocalDate d = LocalDate.parse(startDate);
        for (int i = 0; i < n; i++) {
            dates.add(d.plusDays(i).toString());
        }
        return dates;
    }

    /** Перевіряє, чи перетинається запитуваний діапазон дат з уже існуючими бронюваннями цієї послуги. */
    private boolean rangeOverlaps(String service, String date, int nights) {
        Set<String> requested = new HashSet<>(expandDates(date, nights));
        for (Booking b : repository.findByService(service)) {
            for (String busyDay : expandDates(b.getDate(), b.getNights())) {
                if (requested.contains(busyDay)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Transactional
    public synchronized boolean createBooking(BookingRequest req) {
        if (req.getService() == null || req.getDate() == null) {
            return false;
        }
        int nights = (req.getNights() == null || req.getNights() < 1) ? 1 : req.getNights();

        // Перевіряємо, чи вільний весь діапазон дат (враховуючи кількість ночей)
        if (rangeOverlaps(req.getService(), req.getDate(), nights)) {
            return false; // дата (або одна з ночей) вже зайнята
        }

        Booking booking = new Booking();
        booking.setService(req.getService());
        booking.setDate(req.getDate());
        booking.setNights(nights);
        booking.setTimeSlot(req.getTimeSlot());
        booking.setGuests(req.getGuests());
        booking.setChildren(req.getChildren());
        booking.setTotal(req.getTotal());
        booking.setCustomerName(req.getName());
        booking.setCustomerContact(req.getContact());
        booking.setComment(req.getComment());

        repository.save(booking);
        telegramService.sendBookingNotification(booking);
        return true;
    }
}
