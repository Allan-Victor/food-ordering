# application/port/out  —  OUTBOUND (driven) ports. Interfaces.

The DOMAIN declares what it needs from the outside world; infrastructure
implements it. This inversion is what keeps the domain framework-free.

  SaveOrderPort.java  — Order save(Order order)
  LoadOrderPort.java  — Optional<Order> load(UUID id)
