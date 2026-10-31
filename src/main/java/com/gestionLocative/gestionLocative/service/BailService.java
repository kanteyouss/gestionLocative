package com.gestionLocative.gestionLocative.service;

import com.gestionLocative.gestionLocative.dto.*;
import com.gestionLocative.gestionLocative.entity.*;
import com.gestionLocative.gestionLocative.exception.NotFoundException;
import com.gestionLocative.gestionLocative.exception.RequeteInvalideException;
import com.gestionLocative.gestionLocative.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class BailService extends AbstractCrudService {

    private static final Logger LOG = LoggerFactory.getLogger(BailService.class);
    /** Date butoir utilisée pour un bail sans date de fin lors du contrôle de chevauchement. */
    private static final Date FIN_DES_TEMPS = Date.from(LocalDate.of(9999, 12, 31).atStartOfDay(ZoneId.systemDefault()).toInstant());

    private final BailRepository bailRepository;
    private final AppartementRepository appartementRepository;
    private final LocataireRepository locataireRepository;
    private final RevisionRepository revisionRepository;
    private final BailHistoriqueRepository historiqueRepository;
    private final ImmeubleService immeubleService;
    private final AppartementService appartementService;

    public BailService(BailRepository bailRepository,
                       AppartementRepository appartementRepository,
                       LocataireRepository locataireRepository,
                       RevisionRepository revisionRepository,
                       BailHistoriqueRepository historiqueRepository,
                       @Lazy ImmeubleService immeubleService,
                       @Lazy AppartementService appartementService) {
        super(bailRepository);
        this.bailRepository = bailRepository;
        this.appartementRepository = appartementRepository;
        this.locataireRepository = locataireRepository;
        this.revisionRepository = revisionRepository;
        this.historiqueRepository = historiqueRepository;
        this.immeubleService = immeubleService;
        this.appartementService = appartementService;
    }

    private AppartementResponseDto toAppartementDto(Appartement a) {
        return (AppartementResponseDto) appartementService.toDto(a);
    }

    @Override
    public ResponseDto toDto(Object entity) {
        Bail bail = (Bail) entity;
        Date maintenant = new Date();
        boolean enCours = bail.getStatut() != StatutBail.TERMINE && bail.getDateDebut() != null && !bail.getDateDebut().after(maintenant)
                && (bail.getDateFin() == null || !bail.getDateFin().before(maintenant));
        Locataire l = bail.getLocataire();
        return BailResponseDto.builder()
                .id(bail.getId())
                .dateDebut(bail.getDateDebut())
                .dateFin(bail.getDateFin())
                .montantLoyer(bail.getMontantLoyer())
                .provisionCharges(bail.getProvisionCharges())
                .depotGarantie(bail.getDepotGarantie())
                .enCours(enCours)
                .statut(bail.getStatut() == null ? StatutBail.ACTIF.name() : bail.getStatut().name())
                .appartement(toAppartementDto(bail.getAppartement()))
                .locataire(LocataireResponseDto.builder()
                        .id(l.getId()).nom(l.getNom()).prenom(l.getPrenom())
                        .numero(l.getNumero()).email(l.getEmail()).build())
                .build();
    }

    /** F12 : fin postérieure au début, durée minimale d'un mois. */
    private void verifierDates(Date debut, Date fin) {
        if (fin == null) return;
        if (!fin.after(debut)) {
            throw new RequeteInvalideException("La date de fin doit être postérieure à la date de début.");
        }
        LocalDate d = debut.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        LocalDate f = fin.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        if (f.isBefore(d.plusMonths(1))) {
            throw new RequeteInvalideException("La durée minimum d'un bail est d'un mois.");
        }
    }

    /** F11 : 409 citant le bail bloquant si la période recouvre un autre bail de l'appartement. */
    private void verifierDisponibilite(Long appartementId, Date debut, Date fin, Long excludeId) {
        List<Bail> conflits = bailRepository.findConflictingBailsExcluding(
                appartementId, debut, fin != null ? fin : FIN_DES_TEMPS, excludeId == null ? -1L : excludeId);
        if (!conflits.isEmpty()) {
            Bail c = conflits.get(0);
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd");
            throw new IllegalStateException("L'appartement est déjà occupé par le bail #" + c.getId()
                    + " du " + f.format(c.getDateDebut()) + " au "
                    + (c.getDateFin() != null ? f.format(c.getDateFin()) : "(sans date de fin)") + ".");
        }
    }

    @Override
    public ResponseDto create(RequestDto request) {
        BailRequestDto dto = (BailRequestDto) request;
        verifierDates(dto.getDateDebut(), dto.getDateFin());
        Appartement appartement = appartementRepository.findById(dto.getAppartementId())
                .orElseThrow(() -> new NotFoundException("Appartement non trouvé"));
        Locataire locataire = locataireRepository.findById(dto.getLocataireId())
                .orElseThrow(() -> new NotFoundException("Locataire non trouvé"));
        verifierDisponibilite(dto.getAppartementId(), dto.getDateDebut(), dto.getDateFin(), null);

        Bail bail = Bail.builder()
                .dateDebut(dto.getDateDebut())
                .dateFin(dto.getDateFin())
                .montantLoyer(dto.getMontantLoyer())
                .provisionCharges(dto.getProvisionCharges())
                .depotGarantie(dto.getDepotGarantie())
                .statut(StatutBail.ACTIF)
                .appartement(appartement)
                .locataire(locataire)
                .build();
        return toDto(bailRepository.save(bail));
    }

    @Override
    public ResponseDto update(Long id, RequestDto request) {
        BailRequestDto dto = (BailRequestDto) request;
        Bail bail = bailRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Bail non trouvé"));
        verifierDates(bail.getDateDebut(), dto.getDateFin());
        verifierDisponibilite(bail.getAppartement().getId(), bail.getDateDebut(), dto.getDateFin(), id);
        boolean nonCommence = bail.getDateDebut() != null && bail.getDateDebut().after(new Date());
        if (nonCommence) {
            bail.setMontantLoyer(dto.getMontantLoyer());
        }
        bail.setDateFin(dto.getDateFin());
        return toDto(bailRepository.save(bail));
    }

    public ResponseDto delete(Long id) {
        Bail bail = bailRepository.findById(id)
                .orElseThrow(() -> new NotFoundException(NOT_FOUND_MESSAGE));
        boolean nonCommence = bail.getDateDebut() != null && bail.getDateDebut().after(new Date());
        if (!nonCommence) {
            throw new IllegalArgumentException("Un bail en cours ne peut pas être supprimé, il doit être résilié.");
        }
        bailRepository.deleteById(id);
        return toDto(bail);
    }

    private Date parseDate(String dateStr) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
            sdf.setLenient(false);
            return sdf.parse(dateStr);
        } catch (ParseException e) {
            throw new RequeteInvalideException("Format de date invalide. Utilisez yyyy-MM-dd.");
        }
    }

    private void tracer(Bail bail, String type, Date ancienne, Date nouvelle) {
        historiqueRepository.save(BailHistorique.builder()
                .bail(bail).type(type).ancienneDateFin(ancienne).nouvelleDateFin(nouvelle)
                .dateOperation(new Date()).build());
    }

    /**
     * F13 - Résiliation anticipée : la date de fin est ramenée à la date d'effet.
     * 409 si le bail est déjà résilié/terminé ou n'a pas commencé ; 400 si la date est hors période ou passée.
     */
    @Transactional
    public ResponseDto resiliation(Long id, String dateEffetStr) {
        Bail bail = bailRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Bail non trouvé"));
        if (bail.getStatut() == StatutBail.RESILIE) {
            throw new IllegalStateException("Le bail est déjà résilié.");
        }
        if (bail.getStatut() == StatutBail.TERMINE) {
            throw new IllegalStateException("Le bail est déjà terminé.");
        }
        if (bail.getDateDebut().after(new Date())) {
            throw new IllegalStateException("Le bail n'a pas encore commencé, il ne peut pas être résilié.");
        }
        if (dateEffetStr == null || dateEffetStr.isBlank()) {
            throw new RequeteInvalideException("La date d'effet est obligatoire.");
        }
        Date dateEffet = parseDate(dateEffetStr);
        Date aujourdhui = Date.from(LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant());
        if (dateEffet.before(aujourdhui)) {
            throw new RequeteInvalideException("La date d'effet ne peut pas être antérieure à aujourd'hui.");
        }
        if (dateEffet.before(bail.getDateDebut())
                || (bail.getDateFin() != null && dateEffet.after(bail.getDateFin()))) {
            throw new RequeteInvalideException("La date d'effet doit être comprise dans la période du bail.");
        }
        tracer(bail, "RESILIATION", bail.getDateFin(), dateEffet);
        bail.setDateFin(dateEffet);
        bail.setStatut(StatutBail.RESILIE);
        return toDto(bailRepository.save(bail));
    }

    /**
     * F14 - Renouvellement : prolonge la date de fin ; l'ancienne date de fin est conservée dans l'historique.
     * 409 si le bail est résilié ou si la période ajoutée recouvre un autre bail.
     */
    @Transactional
    public ResponseDto renouvellement(Long id, String nouvelleDateFinStr) {
        Bail bail = bailRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Bail non trouvé"));
        if (bail.getStatut() == StatutBail.RESILIE) {
            throw new IllegalStateException("Un bail résilié ne peut pas être renouvelé.");
        }
        if (nouvelleDateFinStr == null || nouvelleDateFinStr.isBlank()) {
            throw new RequeteInvalideException("La nouvelle date de fin est obligatoire.");
        }
        Date nouvelleDateFin = parseDate(nouvelleDateFinStr);
        if (bail.getDateFin() == null) {
            throw new RequeteInvalideException("Le bail n'a pas de date de fin, il n'y a rien à renouveler.");
        }
        if (!nouvelleDateFin.after(bail.getDateFin())) {
            throw new RequeteInvalideException("La nouvelle date de fin doit être postérieure à la date de fin actuelle.");
        }
        verifierDisponibilite(bail.getAppartement().getId(), bail.getDateFin(), nouvelleDateFin, id);
        tracer(bail, "RENOUVELLEMENT", bail.getDateFin(), nouvelleDateFin);
        bail.setDateFin(nouvelleDateFin);
        if (bail.getStatut() == StatutBail.TERMINE) {
            bail.setStatut(StatutBail.ACTIF);
            bail.setDateCloture(null);
        }
        return toDto(bailRepository.save(bail));
    }

    /**
     * F15 - Révision du loyer : l'ancien loyer est conservé dans l'historique (Revision),
     * ce qui permet aux quittances passées de garder le loyer de l'époque.
     */
    @org.springframework.transaction.annotation.Transactional
    public ResponseDto revision(Long id, BigDecimal nouveauMontant, String dateEffetStr) {
        Bail bail = bailRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Bail non trouvé"));
        if (nouveauMontant == null || nouveauMontant.compareTo(BigDecimal.ZERO) <= 0) {
            throw new RequeteInvalideException("Le nouveau montant doit être supérieur à 0.");
        }
        if (dateEffetStr == null || dateEffetStr.isBlank()) {
            throw new RequeteInvalideException("La date d'effet est obligatoire.");
        }
        Date dateEffet = parseDate(dateEffetStr);
        if (dateEffet.before(bail.getDateDebut())) {
            throw new RequeteInvalideException("La date d'effet ne peut pas être antérieure au début du bail.");
        }
        if (bail.getDateFin() != null && dateEffet.after(bail.getDateFin())) {
            throw new RequeteInvalideException("La date d'effet ne peut pas être postérieure à la fin du bail.");
        }
        if (revisionRepository.existsByBailIdAndDateEffet(id, dateEffet)) {
            throw new IllegalStateException("Une révision existe déjà à cette date.");
        }
        // Premier historique : on fige le loyer initial à la date de début du bail
        if (revisionRepository.findByBailIdOrderByDateEffetAsc(id).isEmpty()) {
            revisionRepository.save(Revision.builder()
                    .bail(bail).dateEffet(bail.getDateDebut()).montantLoyer(bail.getMontantLoyer()).build());
        }
        Revision derniere = revisionRepository.findTopByBailIdOrderByDateEffetDesc(id).orElseThrow();
        if (!dateEffet.after(derniere.getDateEffet())) {
            throw new RequeteInvalideException("La date d'effet doit être postérieure à la dernière révision.");
        }
        revisionRepository.save(Revision.builder()
                .bail(bail).dateEffet(dateEffet).montantLoyer(nouveauMontant).build());
        // Le loyer courant du bail ne change que si la révision est déjà effective
        if (!dateEffet.after(new Date())) {
            bail.setMontantLoyer(nouveauMontant);
        }
        return toDto(bailRepository.save(bail));
    }

    /**
     * F10 - Pagination et tri paramétrable des baux.
     */
    public org.springframework.data.domain.Page<ResponseDto> getAll(int page, int taille, String sort) {
        org.springframework.data.domain.Sort sorting;
        if (sort != null && sort.contains(",")) {
            String[] parts = sort.split(",");
            String property = parts[0].trim();
            org.springframework.data.domain.Sort.Direction direction = "desc".equalsIgnoreCase(parts[1].trim())
                    ? org.springframework.data.domain.Sort.Direction.DESC : org.springframework.data.domain.Sort.Direction.ASC;
            sorting = org.springframework.data.domain.Sort.by(direction, property);
        } else if (sort != null && !sort.isBlank()) {
            sorting = org.springframework.data.domain.Sort.by(sort.trim());
        } else {
            sorting = org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Order.desc("dateDebut"));
        }
        return super.getAll(page, taille, sorting);
    }

    /**
     * F15 - Révision du loyer depuis une Map de paramètres.
     */
    @Transactional
    public ResponseDto revision(Long id, java.util.Map<String, Object> body) {
        if (body == null || body.get("nouveauMontant") == null) {
            throw new RequeteInvalideException("Le nouveau montant est obligatoire.");
        }
        BigDecimal nouveauMontant;
        try {
            nouveauMontant = new BigDecimal(String.valueOf(body.get("nouveauMontant")));
        } catch (Exception e) {
            throw new RequeteInvalideException("Le format du nouveau montant est invalide.");
        }
        String dateEffet = body.get("dateEffet") != null ? String.valueOf(body.get("dateEffet")) : null;
        return revision(id, nouveauMontant, dateEffet);
    }

    /**
     * Loyer applicable à une date donnée (utilisé pour figer le loyer d'une quittance).
     */
    public BigDecimal loyerAu(Long bailId, Date date) {
        Bail bail = bailRepository.findById(bailId)
                .orElseThrow(() -> new NotFoundException("Bail non trouvé"));
        return revisionRepository.findByBailIdOrderByDateEffetAsc(bailId).stream()
                .filter(r -> !r.getDateEffet().after(date))
                .reduce((a, b) -> b)
                .map(Revision::getMontantLoyer)
                .orElse(bail.getMontantLoyer());
    }

    /**
     * F16 - Clôture chaque nuit (00:00) des baux dont la date de fin est dépassée : statut TERMINE.
     * Chaque clôture est journalisée avec l'identifiant du bail.
     */
    @Scheduled(cron = "0 0 0 * * *")
    @Transactional
    public int cloturerBauxTermines() {
        Date aujourdhui = Date.from(LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant());
        List<Bail> aTerminer = bailRepository.findBauxATerminer(aujourdhui);
        for (Bail bail : aTerminer) {
            bail.setStatut(StatutBail.TERMINE);
            bail.setDateCloture(new Date());
            bailRepository.save(bail);
            LOG.info("Bail clôturé (TERMINE) : id={}", bail.getId());
        }
        LOG.info("Clôture nocturne des baux : {} bail(s) clôturé(s).", aTerminer.size());
        return aTerminer.size();
    }
}
