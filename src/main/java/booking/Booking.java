package booking;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "bookings")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Booking {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String service;      // forest, sunset, pool, chan, lazna

    @Column(nullable = false)
    private String date;         // дата заїзду, формат "YYYY-MM-DD"

    @Column(nullable = false)
    private Integer nights = 1;  // кількість ночей (для будинків); 1 для басейну/чану/лазні

    private String timeSlot;     // часовий слот (для чану/лазні)

    private Integer guests;
    private Integer children;
    private Double total;

    private String customerName;
    private String customerContact; // телефон, Instagram або Telegram
    private String comment;
}
