package booking;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
@CrossOrigin(origins = "*") // Щоб фронтенд міг підключатися (в т.ч. відкритий як локальний файл)
public class BookingController {

    private final BookingRepository bookingRepository;
    private final BookingService bookingService;

    public BookingController(BookingRepository bookingRepository, BookingService bookingService) {
        this.bookingRepository = bookingRepository;
        this.bookingService = bookingService;
    }

    @GetMapping("/all")
    public List<Booking> getAllBookings() {
        return bookingRepository.findAll();
    }

    /**
     * Список зайнятих дат по кожній послузі.
     * Фронтенд викликає це під час завантаження сторінки (loadAvailability())
     * і перед відправкою заявки, щоб позначити дні як "зайнято" в календарі.
     */
    @GetMapping("/availability")
    public Map<String, List<String>> getAvailability() {
        return bookingService.getAvailability();
    }

    @PostMapping("/create")
    public ResponseEntity<Map<String, Object>> createBooking(@RequestBody BookingRequest request) {
        boolean success = bookingService.createBooking(request);
        if (success) {
            return ResponseEntity.ok(Map.of("ok", true));
        }
        return ResponseEntity.ok(Map.of("ok", false, "message", "Ця дата вже заброньована"));
    }

    /** Скасування бронювання напряму (наприклад, з майбутньої адмін-панелі). */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> cancelBooking(@PathVariable Long id) {
        if (!bookingRepository.existsById(id)) {
            return ResponseEntity.ok(Map.of("ok", false, "message", "Бронювання не знайдено"));
        }
        bookingRepository.deleteById(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }
}
