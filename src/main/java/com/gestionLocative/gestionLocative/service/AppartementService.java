package com.gestionLocative.gestionLocative.service;

import com.gestionLocative.gestionLocative.dto.*;
import com.gestionLocative.gestionLocative.entity.Appartement;
import com.gestionLocative.gestionLocative.entity.Bail;
import com.gestionLocative.gestionLocative.entity.Immeuble;
import com.gestionLocative.gestionLocative.entity.Proprietaire;
import com.gestionLocative.gestionLocative.exception.NotFoundException;
import com.gestionLocative.gestionLocative.exception.RessourceUtiliseeException;
import com.gestionLocative.gestionLocative.repository.AppartementRepository;
import com.gestionLocative.gestionLocative.repository.BailRepository;
import com.gestionLocative.gestionLocative.repository.ImmeubleRepository;
import com.gestionLocative.gestionLocative.repository.ProprietaireRepository;
import java.math.BigDecimal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class AppartementService extends AbstractCrudService {

    private final AppartementRepository appartementRepository;
    private final ProprietaireRepository proprietaireRepository;
    private final ImmeubleRepository immeubleRepository;
    private final BailRepository bailRepository;

    public AppartementService(AppartementRepository appartementRepository,
                              ProprietaireRepository proprietaireRepository,
                              ImmeubleRepository immeubleRepository,
                              BailRepository bailRepository) {
        super(appartementRepository);
        this.appartementRepository = appartementRepository;
        this.proprietaireRepository = proprietaireRepository;
        this.immeubleRepository = immeubleRepository;
        this.bailRepository = bailRepository;
    }

    public Appartement toEntity(AppartementRequestDto dto, Proprietaire proprietaire, Immeuble immeuble) {
        return Appartement.builder()
                .nom(dto.getNom())
                .surface(dto.getSurface())
                .nbPieces(dto.getNbPieces())
                .etage(dto.getEtage())
                .proprietaire(proprietaire)
                .immeuble(immeuble)
                .build();
    }

    @Override
    public ResponseDto toDto(Object entity) {
        Appartement a = (Appartement) entity;
        Proprietaire p = a.getProprietaire();
        Immeuble i = a.getImmeuble();

        ProprietaireResponseDto proprietaireDto = null;
        if (p != null) {
            proprietaireDto = ProprietaireResponseDto.builder()
                    .id(p.getId())
                    .nom(p.getNom())
                    .prenom(p.getPrenom())
                    .numero(p.getNumero())
                    .email(p.getEmail())
                    .adresse(p.getAdresse())
                    .build();
        }

        Proprietaire proprietaireImmeuble = null;
        if (i.getProprietaireId() != null) {
            proprietaireImmeuble = proprietaireRepository.findById(i.getProprietaireId()).orElse(null);
        }
        ProprietaireResponseDto proprietaireImmeubleDto = null;
        if (proprietaireImmeuble != null) {
            proprietaireImmeubleDto = ProprietaireResponseDto.builder()
                    .id(proprietaireImmeuble.getId())
                    .nom(proprietaireImmeuble.getNom())
                    .prenom(proprietaireImmeuble.getPrenom())
                    .numero(proprietaireImmeuble.getNumero())
                    .email(proprietaireImmeuble.getEmail())
                    .build();
        }

        ImmeubleResponseDto immeubleDto = ImmeubleResponseDto.builder()
                .id(i.getId())
                .nom(i.getNom())
                .adresse(i.getAdresse())
                .nbEtages(i.getNbEtages())
                .proprietaireId(i.getProprietaireId())
                .proprietaire(proprietaireImmeubleDto)
                .nbAppartements(appartementRepository.countByImmeubleId(i.getId()))
                .build();

        return AppartementResponseDto.builder()
                .id(a.getId())
                .nom(a.getNom())
                .surface(a.getSurface())
                .nbPieces(a.getNbPieces())
                .etage(a.getEtage())
                .proprietaire(proprietaireDto)
                .immeuble(immeubleDto)
                .build();
    }

    public Page<ResponseDto> getAll(int page, int taille) {
        return getAll(page, taille, Sort.by("etage", "surface"));
    }

    public Page<ResponseDto> findBySearchCriteria(int page, int taille, Double surfaceMin, Double surfaceMax, Integer pieces) {
        if (surfaceMin != null && surfaceMax != null && surfaceMin > surfaceMax) {
            throw new IllegalArgumentException("La surface minimale ne peut pas être supérieure à la surface maximale.");
        }
        Pageable pageable = PageRequest.of(pageAutorisee(page), tailleAutorisee(taille), Sort.by("etage", "surface"));
        return appartementRepository.rechercher(
                surfaceMin == null ? null : BigDecimal.valueOf(surfaceMin),
                surfaceMax == null ? null : BigDecimal.valueOf(surfaceMax),
                pieces, pageable).map(this::toDto);
    }

    @Override
    public ResponseDto create(RequestDto request) {
        AppartementRequestDto dto = (AppartementRequestDto) request;
        Proprietaire proprietaire = proprietaireRepository.findById(dto.getProprietaireId())
                .orElseThrow(() -> new NotFoundException("Propriétaire non trouvé"));
        Immeuble immeuble = immeubleRepository.findById(dto.getImmeubleId())
                .orElseThrow(() -> new NotFoundException("Immeuble non trouvé"));
        if (dto.getEtage() > immeuble.getNbEtages()) {
            throw new IllegalArgumentException("L'étage (" + dto.getEtage()
                    + ") ne peut pas dépasser le nombre d'étages de l'immeuble (" + immeuble.getNbEtages() + ").");
        }
        return toDto(appartementRepository.save(toEntity(dto, proprietaire, immeuble)));
    }

    @Override
    public ResponseDto update(Long id, RequestDto request) {
        AppartementRequestDto dto = (AppartementRequestDto) request;
        Appartement appartement = appartementRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Appartement non trouvé"));
        Immeuble immeuble = appartement.getImmeuble();
        if (!dto.getImmeubleId().equals(immeuble.getId())) {
            throw new IllegalArgumentException("L'immeuble d'un appartement est figé et ne peut pas être modifié.");
        }
        if (dto.getEtage() > immeuble.getNbEtages()) {
            throw new IllegalArgumentException("L'étage (" + dto.getEtage()
                    + ") ne peut pas dépasser le nombre d'étages de l'immeuble (" + immeuble.getNbEtages() + ").");
        }
        appartement.setNom(dto.getNom());
        appartement.setSurface(dto.getSurface());
        appartement.setNbPieces(dto.getNbPieces());
        appartement.setEtage(dto.getEtage());
        return toDto(appartementRepository.save(appartement));
    }

    public ResponseDto delete(Long id) {
        Appartement appartement = appartementRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Appartement non trouvé"));
        if (bailRepository.existsByAppartementId(id)) {
            throw new RessourceUtiliseeException("Appartement", id, "Bail");
        }
        appartementRepository.deleteById(id);
        return toDto(appartement);
    }

    public List<ResponseDto> getAppartementsByImmeuble(Long immeubleId) {
        Immeuble immeuble = immeubleRepository.findById(immeubleId)
                .orElseThrow(() -> new NotFoundException("Immeuble non trouvé"));
        Date maintenant = new Date();
        return appartementRepository.findByImmeubleIdOrderByEtageAscSurfaceAsc(immeubleId).stream()
                .map(a -> {
                    AppartementResponseDto dto = (AppartementResponseDto) toDto(a);
                    boolean occupe = bailRepository.findByAppartementId(a.getId()).stream()
                            .anyMatch(b -> b.getDateDebut() != null && !b.getDateDebut().after(maintenant)
                                    && (b.getDateFin() == null || !b.getDateFin().before(maintenant)));
                    dto.setEtageOccupe(occupe);
                    return dto;
                })
                .collect(Collectors.toList());
    }

    public List<ResponseDto> getBauxByAppartement(Long id) {
        Appartement appartement = appartementRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Appartement non trouvé"));
        Date maintenant = new Date();
        return bailRepository.findByAppartementIdOrderByDateDebutDesc(id).stream()
                .map(bail -> BailResponseDto.builder()
                        .id(bail.getId())
                        .dateDebut(bail.getDateDebut())
                        .dateFin(bail.getDateFin())
                        .montantLoyer(bail.getMontantLoyer())
                        .provisionCharges(bail.getProvisionCharges())
                        .depotGarantie(bail.getDepotGarantie())
                        .enCours(bail.getDateDebut() != null && !bail.getDateDebut().after(maintenant)
                                && (bail.getDateFin() == null || !bail.getDateFin().before(maintenant)))
                        .locataire(LocataireResponseDto.builder()
                                .id(bail.getLocataire().getId())
                                .nom(bail.getLocataire().getNom())
                                .prenom(bail.getLocataire().getPrenom())
                                .numero(bail.getLocataire().getNumero())
                                .email(bail.getLocataire().getEmail())
                                .build())
                        .build())
                .collect(Collectors.toList());
    }
}